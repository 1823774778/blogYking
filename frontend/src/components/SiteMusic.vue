<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import http from '../api'
import { state, toast } from '../state'

const MODES = [
  { id: 'loop', label: '列表循环', hint: '播完最后一首后回到第一首' },
  { id: 'single', label: '单曲循环', hint: '当前歌曲循环播放' },
  { id: 'shuffle', label: '随机播放', hint: '随机选择队列中的歌曲' },
  { id: 'sequence', label: '顺序播放', hint: '播完队列后停止' }
]
const SUGGESTIONS = ['晴天', '起风了', '慢慢喜欢你', '后来', '夜曲', '稻香', '孤勇者', '光年之外']
const STORAGE_KEY = 'deek.music.player.v1'
const FAB_OFFSET_KEY = 'deek.music.fab.offset.v1'
const SYNC_CHANNEL_NAME = 'deek.music.sync.v1'
const SYNC_STORAGE_KEY = 'deek.music.sync.message.v1'
const SYNC_LEADER_LOCK = 'deek.music.leader.lock.v1'
const FALLBACK_LEADER_KEY = 'deek.music.fallback-leader.v1'
const FALLBACK_LEADER_TTL = 6000
const FALLBACK_LEADER_HEARTBEAT = 1500
const TAB_ID = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
const CHART_TTL_MILLIS = 10 * 60 * 1000
const FALLBACK_PLATFORM_LABELS = {
  wy: '网易音乐',
  tx: 'QQ 音乐',
  kw: '酷我音乐',
  kg: '酷狗音乐',
  mg: '咪咕音乐'
}

function platformColor(code) {
  let hash = 0
  for (const char of String(code || '')) hash = (hash * 31 + char.charCodeAt(0)) % 360
  return `hsl(${hash} 62% 48%)`
}

const audio = ref(null)
const panelOpen = ref(false)
const queue = ref([])
const currentIndex = ref(-1)
const playing = ref(false)
const loadingTrack = ref(false)
const progress = ref(0)
const duration = ref(0)
const mode = ref('loop')
const volume = ref(0.8)
const muted = ref(false)
const volumePopoverOpen = ref(false)
const volumeWrap = ref(null)
const searchKeyword = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const searchError = ref('')
const searchFocused = ref(false)
const view = ref('charts')
const activePlatform = ref('wy')
const nowTab = ref('lyrics')
const lastBrowseView = ref('charts')
const lyricLines = ref([])
const lyricHasTimed = ref(false)
const lyricLoading = ref(false)
const lyricError = ref('')
const lyricProvider = ref('')
const activeLyricIndex = ref(-1)
const lyricScroll = ref(null)
const playlistItems = ref([])
const playlistTotal = ref(0)
const playlistLoading = ref(false)
const playlistError = ref('')
const playlistSeq = ref(0)
const playlistLoadedKey = ref('')
const playlistLoadedAt = ref(0)
const activePlaylistId = ref('')
const playlistDetail = ref(null)
const playlistDetailLoading = ref(false)
const playlistDetailError = ref('')
const playlistDetailSeq = ref(0)
const platformMenuOpen = ref(false)
const rankingData = ref(null)
const rankingLoading = ref(false)
const rankingError = ref('')
const rankingSeq = ref(0)
const rankingLoadedKey = ref('')
const rankingLoadedAt = ref(0)
const sourceDetails = ref([])
const sourceMeta = ref({})
const sourceDetailsLoaded = ref(false)
const sourceDetailsLoading = ref(false)
const localTracks = ref([])
const localLoading = ref(false)
const localError = ref('')
const localLoaded = ref(false)
const panelPlacement = ref(null)
const fabOffset = ref({ x: 0, y: 0 })
const fabDragging = ref(false)
const fabMoved = ref(false)
const fabHovered = ref(false)
const compactViewport = ref(false)
const searchInput = ref(null)
const chartBody = ref(null)
const platformMenu = ref(null)
const fab = ref(null)
let searchTimer = null
let searchSeq = 0
let playSeq = 0
let fabDrag = null
let fabMoveTimer = null
let fabFocusTimer = null
let platformInitialized = false
let sourceDetailsSeq = 0
let tabChannel = null
let tabLeader = false
let tabLeaderId = ''
let applyingSyncState = false
let releaseLeaderLock = null
let syncStateTimer = null
let lastSyncTickAt = 0
let fallbackLeaderTimer = null
let fallbackLeaseOwned = false
let tabSyncStarted = false
let tabClosing = false
const playlistDetailCache = new Map()
const lyricCache = new Map()
let lyricSeq = 0
let lyricRequestKey = ''
let platformRestorePending = false
let sourceWatchReady = false
let restoredPlatform = ''
let restoredView = ''
let restoredPlaylistId = ''

const sourceUrl = computed(() => String(state.site.musicSourceUrl || '').trim())
const hasSource = computed(() => Boolean(sourceUrl.value))
const sourceName = computed(() => String(state.site.musicSourceName || 'LX 音源').trim())
const sourceVersion = computed(() => String(state.site.musicSourceVersion || '').trim())
const localCount = computed(() => localTracks.value.length)
const localEmpty = computed(() => localLoaded.value && !localTracks.value.length && !localLoading.value)
const localPending = computed(() => localLoading.value || (!localLoaded.value && !localError.value))
const sourcePlatformCodes = computed(() => {
  const seen = new Set()
  return String(state.site.musicSourcePlatforms || '')
    .split(',')
    .map((code) => code.trim().toLowerCase())
    .filter((code) => {
      if (!code || seen.has(code)) return false
      seen.add(code)
      return true
    })
})
const contentMode = computed(() => String(state.site.musicContentMode || '').trim() === 'local' ? 'local' : 'source')
const sourceActive = computed(() => hasSource.value && contentMode.value === 'source')
const localActive = computed(() => contentMode.value === 'local' || !hasSource.value)
// 平台名称、actions、音质都来自当前音源；前端只在接口返回前用平台代码兜底。
const sourcePlatforms = computed(() => {
  const details = Array.isArray(sourceDetails.value) ? sourceDetails.value : []
  const platforms = sourceDetailsLoaded.value
    ? details.map((item) => {
        const code = String(item?.code || '').trim().toLowerCase()
        if (!code) return null
        return {
          code,
          label: String(item?.label || item?.name || code).trim() || code,
          actions: Array.isArray(item?.actions) ? item.actions : [],
          qualities: Array.isArray(item?.qualities) ? item.qualities : [],
          canPlay: item?.canPlay !== false,
          charts: item?.charts === true,
          playlists: item?.playlists === true,
          search: item?.search === true,
          color: platformColor(code)
        }
      }).filter(Boolean)
    : sourcePlatformCodes.value.map((code) => ({
        code,
        label: FALLBACK_PLATFORM_LABELS[code] || code.toUpperCase(),
        actions: [],
        qualities: [],
        canPlay: true,
        charts: true,
        playlists: true,
        search: code === 'wy',
        color: platformColor(code)
      }))
  return platforms.filter((platform) => platform.canPlay)
})
// 歌单平台必须同时满足：音源声明了该平台，且本站有对应的歌单读取适配。
const playlistPlatforms = computed(() => sourcePlatforms.value.filter((platform) => platform.playlists))
const chartPlatforms = computed(() => playlistPlatforms.value)
const hasCharts = computed(() => sourceActive.value && playlistPlatforms.value.length > 0)
const rankingPlatforms = computed(() => sourcePlatforms.value.filter((platform) => platform.charts))
const hasRanking = computed(() => sourceActive.value && rankingPlatforms.value.length > 0)
// 下拉只展示当前音源真实支持的平台，同一平台同时服务歌单与排行榜。
const menuPlatforms = computed(() => sourcePlatforms.value
  .filter((platform) => platform.playlists || platform.charts))
const menuViewTarget = computed(() => view.value === 'ranking' ? 'ranking' : 'charts')
// 搜索使用当前音源实际声明的平台；后端并行检索后合并，不再固定网易云。
const searchPlatforms = computed(() => sourcePlatforms.value.filter((platform) => platform.search))
const searchPlatformSummary = computed(() => searchPlatforms.value.length > 1
  ? `${searchPlatforms.value.length} 个平台`
  : (searchPlatforms.value[0]?.label || '当前音源'))
const hasSearch = computed(() => sourceActive.value && searchPlatforms.value.length > 0)
const playlistCards = computed(() => playlistItems.value)
const hasPlaylists = computed(() => hasCharts.value)
const sourceFeatureLabel = computed(() => {
  const features = []
  if (hasPlaylists.value) features.push('歌单')
  if (hasRanking.value) features.push('排行榜')
  if (hasSearch.value) features.push('搜索')
  features.push('播放列表', '音量')
  return features.join(' / ')
})
const activeChartTracks = computed(() => Array.isArray(playlistDetail.value?.tracks) ? playlistDetail.value.tracks : [])
const activePlatformInfo = computed(() => sourcePlatforms.value.find((platform) => platform.code === activePlatform.value))
const activePlatformLabel = computed(() => platformDisplayName(activePlatform.value))
const activePlaylistCard = computed(() => playlistCards.value.find((item) =>
  item.platform === activePlatform.value && String(item.id) === activePlaylistId.value) || null)
const activePlaylist = computed(() => {
  const base = playlistDetail.value || activePlaylistCard.value
  if (!base) return null
  return { ...base, color: activePlatformInfo.value?.color || platformColor(base.platform) }
})
const activePlaylistTitle = computed(() => playlistDetail.value?.title || activePlaylistCard.value?.title || `${activePlatformLabel.value}歌单`)
const activePlaylistCover = computed(() => playlistDetail.value?.cover || activePlaylistCard.value?.cover || '')
const rankingTracks = computed(() => Array.isArray(rankingData.value?.tracks) ? rankingData.value.tracks : [])
const rankingTitle = computed(() => {
  const raw = String(rankingData.value?.chartTitle || '').trim()
  return raw || `${activePlatformLabel.value}热歌榜`
})
const activePlaylistSummary = computed(() => {
  const count = activeChartTracks.value.length
  const creator = String(activePlaylist.value?.creator || '').trim()
  return `${creator ? creator + ' · ' : ''}${count ? count + ' 首 · 点击歌曲加入队列' : '正在整理歌单…'}`
})
const panelStyle = computed(() => {
  const placement = panelPlacement.value
  if (!placement) return {}
  const style = {
    '--sm-panel-x': placement.compact
      ? (placement.mobileSide === 'left' ? '-24px' : '24px')
      : (placement.horizontal === 'left' ? '12px' : '-12px'),
    '--sm-panel-y': placement.compact ? '14px' : (placement.vertical === 'above' ? '12px' : '-12px'),
    '--sm-panel-origin': placement.compact
      ? `${placement.mobileSide === 'left' ? 'left' : 'right'} bottom`
      : `${placement.horizontal === 'left' ? 'right' : 'left'} ${placement.vertical === 'above' ? 'bottom' : 'top'}`
  }
  if (!placement.compact) {
    style.width = placement.width
    style.maxHeight = placement.maxHeight
    if (placement.horizontal === 'left') {
      style.right = placement.right
      style.left = 'auto'
    } else {
      style.left = placement.left
      style.right = 'auto'
    }
    if (placement.vertical === 'above') {
      style.bottom = placement.bottom
      style.top = 'auto'
    } else {
      style.top = placement.top
      style.bottom = 'auto'
    }
  }
  return style
})
const fabStyle = computed(() => {
  const offset = fabOffset.value
  if (!offset || (!offset.x && !offset.y)) return {}
  return { transform: `translate(${offset.x}px, ${offset.y}px)` }
})
const sourceQualities = computed(() => {
  const fromSource = Array.isArray(sourceMeta.value?.qualities) ? sourceMeta.value.qualities : []
  const qualities = fromSource.length ? fromSource : String(state.site.musicSourceQualities || '128k,320k,flac').split(',')
  return qualities
    .map((item) => String(item || '').trim())
    .filter(Boolean)
    .filter((item, index, list) => list.indexOf(item) === index)
})
const resolveQuality = computed(() => sourceQualities.value.includes('320k') ? '320k' : (sourceQualities.value[0] || '128k'))

function playbackQualityFor(track) {
  const platform = sourcePlatforms.value.find((item) => item.code === String(track?.source || '').trim())
  const supported = Array.isArray(platform?.qualities) && platform.qualities.length
    ? platform.qualities
    : sourceQualities.value
  if (supported.includes('320k')) return '320k'
  if (supported.includes('128k')) return '128k'
  return supported[0] || '128k'
}
const showPlayer = computed(() =>
  state.site.musicEnabled === 'true' && (hasSource.value || localCount.value > 0)
)
const footerSourceLabel = computed(() => {
  if (sourceActive.value) return sourceName.value + (sourceVersion.value ? ` v${sourceVersion.value}` : '')
  return localCount.value ? `本地音乐 · ${localCount.value} 首` : '站点音乐'
})
const currentTrack = computed(() => queue.value[currentIndex.value] || null)
const currentDuration = computed(() => duration.value || currentTrack.value?.duration || 0)
const progressPercent = computed(() => currentDuration.value > 0
  ? Math.min(100, Math.max(0, (progress.value / currentDuration.value) * 100))
  : 0)
const volumePercent = computed(() => Math.round((muted.value ? 0 : volume.value) * 100))
const modeMeta = computed(() => MODES.find((item) => item.id === mode.value) || MODES[0])
const searchHintVisible = computed(() => !searchKeyword.value.trim() && !searchResults.value.length && !searchLoading.value)
const playedQueue = computed(() => queue.value
  .map((track, index) => ({ track, index }))
  .filter((item) => item.track.played))

function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '00:00'
  const total = Math.floor(seconds)
  const min = String(Math.floor(total / 60)).padStart(2, '0')
  const sec = String(total % 60).padStart(2, '0')
  return `${min}:${sec}`
}

function coverLetter(track) {
  return String(track?.title || track?.artist || '♪').trim().slice(0, 1).toUpperCase() || '♪'
}

function loadPersistedState() {
  try {
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')
    if (Array.isArray(raw.queue)) {
      queue.value = raw.queue
        .filter((track) => track && track.id)
        .slice(0, 50)
        .map((track) => ({
          id: String(track.id),
          source: track.source || 'wy',
          title: track.title || '未命名歌曲',
          artist: track.artist || '未知歌手',
          album: track.album || '',
          cover: track.cover || '',
          duration: Number(track.duration) || 0,
          fee: Number(track.fee) || 0,
          pageUrl: track.pageUrl || '',
          played: Boolean(track.played),
          originSource: track.originSource || '',
          originId: track.originId || '',
          fallbackOriginalSource: track.fallbackOriginalSource || '',
          url: track.source === 'local' ? String(track.url || '') : '',
          urlError: '',
          urlLoading: false
        }))
    }
    if (raw.mode && MODES.some((item) => item.id === raw.mode)) mode.value = raw.mode
    if (Number.isFinite(Number(raw.volume))) volume.value = Math.min(1, Math.max(0, Number(raw.volume)))
    if (typeof raw.muted === 'boolean') muted.value = raw.muted
    const index = Number(raw.currentIndex)
    currentIndex.value = queue.value.length
      ? (Number.isInteger(index) && index >= 0 && index < queue.value.length ? index : 0)
      : -1
    const restored = queue.value[currentIndex.value]
    if (restored) restored.played = true
    if (typeof raw.platform === 'string') restoredPlatform = raw.platform.trim()
    if (typeof raw.view === 'string') restoredView = raw.view.trim()
    if (typeof raw.nowTab === 'string') nowTab.value = raw.nowTab === 'queue' ? 'queue' : 'lyrics'
    if (typeof raw.activePlaylistId === 'string') restoredPlaylistId = raw.activePlaylistId.trim()
    if (typeof raw.lastBrowseView === 'string' && ['charts', 'ranking', 'search', 'local', 'playlist'].includes(raw.lastBrowseView)) {
      lastBrowseView.value = raw.lastBrowseView
    }
  } catch (e) {
    /* 本地状态损坏时忽略即可 */
  }
}

function persistState() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      mode: mode.value,
      volume: volume.value,
      muted: muted.value,
      currentIndex: currentIndex.value,
      platform: activePlatform.value,
      view: view.value,
      nowTab: nowTab.value,
      activePlaylistId: activePlaylistId.value,
      lastBrowseView: lastBrowseView.value,
      queue: queue.value.slice(0, 50).map((track) => ({
        id: track.id,
        source: track.source || 'wy',
        title: track.title,
        artist: track.artist,
        album: track.album,
        cover: track.cover,
        duration: track.duration,
        fee: track.fee,
        pageUrl: track.pageUrl,
        played: Boolean(track.played),
        originSource: track.originSource || '',
        originId: track.originId || '',
        fallbackOriginalSource: track.fallbackOriginalSource || '',
        url: track.source === 'local' ? track.url : ''
      }))
    }))
  } catch (e) {
    /* 隐私模式下 localStorage 可能不可用 */
  }
}

function syncTrackSnapshot(track) {
  return {
    id: String(track.id || ''),
    source: track.source || 'wy',
    title: track.title || '未命名歌曲',
    artist: track.artist || '未知歌手',
    album: track.album || '',
    cover: track.cover || '',
    duration: Number(track.duration) || 0,
    fee: Number(track.fee) || 0,
    pageUrl: track.pageUrl || '',
    played: Boolean(track.played),
    originSource: track.originSource || '',
    originId: track.originId || '',
    fallbackOriginalSource: track.fallbackOriginalSource || '',
    fallbackLabel: track.fallbackLabel || '',
    quality: track.quality || '',
    url: String(track.url || ''),
    urlError: track.urlError || '',
    urlLoading: Boolean(track.urlLoading)
  }
}

function normalizeSyncedTrack(track) {
  const source = String(track?.source || 'wy')
  return {
    id: String(track?.id || ''),
    source,
    title: track?.title || '未命名歌曲',
    artist: track?.artist || '未知歌手',
    album: track?.album || '',
    cover: track?.cover || '',
    duration: Number(track?.duration) || 0,
    fee: Number(track?.fee) || 0,
    pageUrl: track?.pageUrl || '',
    played: Boolean(track?.played),
    originSource: track?.originSource || '',
    originId: track?.originId || '',
    fallbackOriginalSource: track?.fallbackOriginalSource || '',
    fallbackLabel: track?.fallbackLabel || '',
    quality: track?.quality || '',
    url: String(track?.url || ''),
    urlError: track?.urlError || '',
    urlLoading: Boolean(track?.urlLoading)
  }
}

function buildSyncState() {
  return {
    queue: queue.value.slice(0, 100).map(syncTrackSnapshot),
    currentIndex: currentIndex.value,
    playing: Boolean(playing.value),
    loadingTrack: Boolean(loadingTrack.value),
    progress: Number(progress.value) || 0,
    duration: Number(currentDuration.value) || 0,
    mode: mode.value,
    volume: volume.value,
    muted: muted.value,
    updatedAt: Date.now()
  }
}

function postSyncMessage(message) {
  const envelope = { ...message, senderId: TAB_ID, sentAt: Date.now() }
  if (tabChannel) {
    try {
      tabChannel.postMessage(envelope)
      return
    } catch (e) {
      /* BroadcastChannel 被关闭时退回本地存储消息 */
    }
  }
  try {
    localStorage.setItem(SYNC_STORAGE_KEY, JSON.stringify(envelope))
  } catch (e) {
    /* 隐私模式下没有跨标签同步能力 */
  }
}

function sendSyncState() {
  if (!tabLeader) return
  tabLeaderId = TAB_ID
  postSyncMessage({ type: 'state', state: buildSyncState() })
}

function requestRemotePlayback(action, payload = {}) {
  postSyncMessage({ type: 'action', action, payload })
}

function scheduleSyncState() {
  if (!tabLeader || applyingSyncState) return
  if (syncStateTimer) return
  syncStateTimer = setTimeout(() => {
    syncStateTimer = null
    if (tabLeader) sendSyncState()
  }, 40)
}

function applySyncState(state) {
  if (!state || tabLeader) return
  const tracks = (Array.isArray(state.queue) ? state.queue : [])
    .map(normalizeSyncedTrack)
    .filter((track) => track.id)
  const index = Number(state.currentIndex)
  applyingSyncState = true
  queue.value = tracks
  currentIndex.value = tracks.length
    ? (Number.isInteger(index) && index >= 0 && index < tracks.length ? index : 0)
    : -1
  playing.value = Boolean(state.playing)
  loadingTrack.value = Boolean(state.loadingTrack)
  mode.value = MODES.some((item) => item.id === state.mode) ? state.mode : 'loop'
  if (Number.isFinite(Number(state.volume))) volume.value = Math.min(1, Math.max(0, Number(state.volume)))
  if (typeof state.muted === 'boolean') muted.value = state.muted
  duration.value = Number(state.duration) || currentTrack.value?.duration || 0
  progress.value = Math.max(0, Number(state.progress) || 0)
  applyingSyncState = false
  if (audio.value && !audio.value.paused) audio.value.pause()
  syncLyricIndex(progress.value)
}

function applySyncTick(message) {
  if (!message || tabLeader) return
  progress.value = Math.max(0, Number(message.progress) || 0)
  if (Number.isFinite(Number(message.duration)) && Number(message.duration) > 0) {
    duration.value = Number(message.duration)
  }
  syncLyricIndex(progress.value)
}

function handleSyncAction(message) {
  if (!tabLeader) return
  const action = message?.action
  const payload = message?.payload && typeof message.payload === 'object' ? message.payload : {}
  if (action === 'play-track' && payload.track) {
    const index = addTrackToQueue(normalizeSyncedTrack(payload.track))
    if (index >= 0) playIndex(index, true)
  } else if (action === 'play-collection') {
    const tracks = (Array.isArray(payload.tracks) ? payload.tracks : [])
      .map(normalizeSyncedTrack)
      .filter((track) => track.id)
    playCollection(tracks)
  } else if (action === 'play-index') {
    playIndex(Number(payload.index), payload.autoplay !== false)
  } else if (action === 'toggle') {
    togglePlayback()
  } else if (action === 'step') {
    const delta = Number(payload.delta)
    if (Number.isFinite(delta) && delta !== 0) step(delta)
  } else if (action === 'cycle-mode') {
    cycleMode()
  } else if (action === 'seek') {
    const percent = Number(payload.percent)
    if (Number.isFinite(percent)) seekToTime((percent / 100) * currentDuration.value)
  } else if (action === 'seek-time') {
    seekToTime(Number(payload.time), payload.autoplay === true)
  } else if (action === 'set-volume') {
    applyVolumeValue(Number(payload.value))
  } else if (action === 'set-muted') {
    setMutedValue(Boolean(payload.muted))
  } else if (action === 'remove') {
    removeTrack(Number(payload.index))
  } else if (action === 'clear') {
    clearQueue()
  }
  scheduleSyncState()
}

function handleSyncMessage(message) {
  if (!message || message.senderId === TAB_ID) return
  if (message.type === 'hello') {
    if (tabLeader) sendSyncState()
    return
  }
  if (message.type === 'state') {
    if (tabLeader) return
    tabLeaderId = message.senderId || ''
    applySyncState(message.state)
    return
  }
  if (message.type === 'tick') {
    if (tabLeader) return
    if (!tabLeaderId) tabLeaderId = message.senderId || ''
    if (message.senderId === tabLeaderId) applySyncTick(message)
    return
  }
  if (message.type === 'action' && tabLeader) {
    handleSyncAction(message)
  }
}

function onSyncStorage(event) {
  if (!event.key) return
  if (event.key === SYNC_STORAGE_KEY && event.newValue) {
    try {
      handleSyncMessage(JSON.parse(event.newValue))
    } catch (e) {
      /* 忽略损坏的跨标签消息 */
    }
    return
  }
  if (event.key !== FALLBACK_LEADER_KEY || !fallbackLeaderTimer || !event.newValue) return
  try {
    const lease = JSON.parse(event.newValue)
    if (lease?.id && lease.id !== TAB_ID && tabLeader) {
      tabLeader = false
      tabLeaderId = lease.id
      fallbackLeaseOwned = false
    }
  } catch (e) {
    /* 忽略损坏的 leader 租约 */
  }
}

function beginTabLeadership() {
  if (tabClosing || tabLeader) return
  tabLeader = true
  tabLeaderId = TAB_ID
  sendSyncState()
  resumeLeaderPlayback()
}

async function acquireTabLeadership() {
  if (!navigator.locks?.request) {
    startFallbackLeaderElection()
    return
  }
  try {
    await navigator.locks.request(SYNC_LEADER_LOCK, { mode: 'exclusive' }, async () => {
      beginTabLeadership()
      await new Promise((resolve) => { releaseLeaderLock = resolve })
      releaseLeaderLock = null
    })
  } catch (e) {
    startFallbackLeaderElection()
  }
}

function readFallbackLease() {
  try {
    const lease = JSON.parse(localStorage.getItem(FALLBACK_LEADER_KEY) || 'null')
    if (lease?.id && Number.isFinite(Number(lease.at))) return lease
  } catch (e) {
    /* 忽略损坏的 leader 租约 */
  }
  return null
}

function writeFallbackLease() {
  try {
    localStorage.setItem(FALLBACK_LEADER_KEY, JSON.stringify({ id: TAB_ID, at: Date.now() }))
    fallbackLeaseOwned = true
  } catch (e) {
    /* 隐私模式下无法选出跨标签播放主控 */
  }
}

function startFallbackLeaderElection() {
  if (fallbackLeaderTimer) return
  const lease = readFallbackLease()
  if (!lease || lease.id === TAB_ID || Date.now() - Number(lease.at) > FALLBACK_LEADER_TTL) {
    writeFallbackLease()
    beginTabLeadership()
  } else {
    tabLeaderId = lease.id
  }
  fallbackLeaderTimer = setInterval(() => {
    if (tabClosing) return
    const current = readFallbackLease()
    if (tabLeader) {
      writeFallbackLease()
      return
    }
    if (!current || Date.now() - Number(current.at) > FALLBACK_LEADER_TTL) {
      writeFallbackLease()
      beginTabLeadership()
    } else if (current.id !== tabLeaderId) {
      tabLeaderId = current.id
    }
  }, FALLBACK_LEADER_HEARTBEAT)
}

function initTabSync() {
  if (tabSyncStarted) return
  tabSyncStarted = true
  try {
    tabChannel = new BroadcastChannel(SYNC_CHANNEL_NAME)
    tabChannel.addEventListener('message', (event) => handleSyncMessage(event.data))
  } catch (e) {
    tabChannel = null
  }
  window.addEventListener('storage', onSyncStorage)
  postSyncMessage({ type: 'hello' })
  acquireTabLeadership()
}

function releaseTabLeadership() {
  tabClosing = true
  if (releaseLeaderLock) {
    const release = releaseLeaderLock
    releaseLeaderLock = null
    release()
  }
  if (fallbackLeaderTimer) {
    clearInterval(fallbackLeaderTimer)
    fallbackLeaderTimer = null
  }
  if (fallbackLeaseOwned) {
    try {
      localStorage.removeItem(FALLBACK_LEADER_KEY)
    } catch (e) {
      /* 隐私模式下忽略 */
    }
    fallbackLeaseOwned = false
  }
  tabLeader = false
}

watch(queue, persistState, { deep: true })
watch([mode, volume, muted], persistState)
watch([activePlatform, view, nowTab, activePlaylistId, lastBrowseView], persistState)
watch(view, (next) => {
  volumePopoverOpen.value = false
  if (['charts', 'ranking', 'search', 'local', 'playlist'].includes(next)) lastBrowseView.value = next
  if (next === 'now') loadLyrics()
})
watch(nowTab, (next) => {
  if (next === 'lyrics' && view.value === 'now') loadLyrics()
})
watch(() => [currentTrack.value?.source, currentTrack.value?.id, currentTrack.value?.title, currentTrack.value?.artist].join('|'), () => {
  activeLyricIndex.value = -1
  lyricRequestKey = ''
  if (view.value === 'now' && nowTab.value === 'lyrics') loadLyrics(true)
})
watch(volume, (value) => {
  if (audio.value) audio.value.volume = Math.min(1, Math.max(0, value))
})
watch(muted, (value) => {
  if (audio.value) audio.value.muted = value
})
watch(queue, scheduleSyncState, { deep: true })
watch([currentIndex, playing, loadingTrack, mode, volume, muted, duration], scheduleSyncState)

function isViewAllowed(candidate) {
  if (!candidate) return false
  if (candidate === 'local') return localActive.value
  if (candidate === 'charts') return hasCharts.value
  if (candidate === 'playlist') return hasCharts.value && Boolean(activePlaylistId.value)
  if (candidate === 'ranking') return hasRanking.value
  if (candidate === 'search') return hasSearch.value
  if (candidate === 'now') return Boolean(currentTrack.value)
  return false
}

function resolveInitialView(candidate) {
  if (candidate === 'queue') return currentTrack.value ? 'now' : defaultView()
  if (candidate === 'now' && currentTrack.value) return 'now'
  if (isViewAllowed(candidate)) return candidate
  return defaultView()
}

function reconcileAvailableView() {
  // 音源能力尚未返回时不要用空平台列表校正用户刷新前选择的平台/视图。
  if (platformRestorePending && !sourceDetailsLoaded.value && contentMode.value !== 'local') return
  if ((playlistPlatforms.value.length || rankingPlatforms.value.length) && !platformInitialized) {
    // 默认选中更稳定的解析通道；真实歌单不预加载歌曲。刷新后优先恢复用户上次选择的平台。
    const remembered = String(restoredPlatform || '').trim()
    const rememberedValid = sourcePlatforms.value.some((platform) => platform.code === remembered)
    activePlatform.value = rememberedValid ? remembered : preferredPlatform()
    platformInitialized = true
  } else if (view.value === 'ranking' && !rankingPlatforms.value.some((platform) => platform.code === activePlatform.value)) {
    activePlatform.value = preferredPlatform()
  } else if (view.value !== 'ranking' && view.value !== 'now' && hasCharts.value
      && !playlistPlatforms.value.some((platform) => platform.code === activePlatform.value)) {
    activePlatform.value = preferredPlatform()
  }
  if (view.value === 'queue') view.value = currentTrack.value ? 'now' : defaultView()
  if (view.value === 'playlist' && !activePlaylistId.value) {
    view.value = 'charts'
  }
  if (view.value === 'now' && !currentTrack.value) {
    view.value = defaultView()
  }
  if (!panelOpen.value) return
  if (isViewAllowed(view.value)) {
    if (view.value === 'charts') loadPlaylists()
    else if (view.value === 'playlist') loadPlaylistDetail()
    else if (view.value === 'ranking') loadRanking()
    else if (view.value === 'now') loadLyrics()
    return
  }
  view.value = defaultView()
  if (view.value === 'local') loadLocalTracks(false)
  else if (view.value === 'charts') loadPlaylists()
  else if (view.value === 'ranking') loadRanking()
  else if (view.value === 'now') loadLyrics()
}

watch([contentMode, hasSource, hasCharts, hasRanking, hasSearch, localActive], reconcileAvailableView)
watch(sourcePlatformCodes, reconcileAvailableView)
watch(sourceDetails, reconcileAvailableView, { deep: true })
watch(sourceUrl, () => {
  if (!sourceWatchReady) {
    sourceWatchReady = true
    // 首次拿到站点音乐配置后补齐音源能力；不要把它当成“用户切换了音源”。
    loadSourceCapabilities()
    return
  }
  platformInitialized = false
  platformRestorePending = false
  restoredPlatform = ''
  restoredView = ''
  restoredPlaylistId = ''
  if (view.value === 'playlist') view.value = 'charts'
  playlistSeq.value += 1
  playlistDetailSeq.value += 1
  playlistItems.value = []
  playlistTotal.value = 0
  playlistLoading.value = false
  playlistError.value = ''
  playlistLoadedKey.value = ''
  playlistLoadedAt.value = 0
  activePlaylistId.value = ''
  playlistDetail.value = null
  playlistDetailLoading.value = false
  playlistDetailError.value = ''
  playlistDetailCache.clear()
  platformMenuOpen.value = false
  rankingSeq.value += 1
  rankingData.value = null
  rankingLoading.value = false
  rankingError.value = ''
  rankingLoadedKey.value = ''
  rankingLoadedAt.value = 0
  sourceDetailsSeq += 1
  sourceDetails.value = []
  sourceMeta.value = {}
  sourceDetailsLoaded.value = false
  sourceDetailsLoading.value = false
  loadSourceCapabilities()
  reconcileAvailableView()
})
watch(contentMode, (mode) => {
  if (mode === 'source') loadSourceCapabilities()
  else {
    platformRestorePending = false
    sourceDetailsSeq += 1
    sourceDetails.value = []
    sourceMeta.value = {}
    sourceDetailsLoaded.value = false
    sourceDetailsLoading.value = false
  }
})

function apiErrorMessage(error, fallback) {
  return error?.message || fallback
}

function defaultView() {
  if (sourceActive.value) {
    if (hasCharts.value) return 'charts'
    if (hasRanking.value) return 'ranking'
    if (hasSearch.value) return 'search'
    return 'now'
  }
  return 'local'
}

async function loadSourceCapabilities() {
  if (!sourceActive.value) {
    sourceDetailsSeq += 1
    sourceDetails.value = []
    sourceMeta.value = {}
    sourceDetailsLoaded.value = false
    sourceDetailsLoading.value = false
    return
  }
  const seq = ++sourceDetailsSeq
  sourceDetailsLoading.value = true
  try {
    const res = await http.get('/music/platforms', { timeout: 20000 })
    if (seq !== sourceDetailsSeq) return
    sourceMeta.value = res.data?.source && typeof res.data.source === 'object' ? res.data.source : {}
    sourceDetailsLoaded.value = true
    sourceDetails.value = Array.isArray(res.data?.platforms) ? res.data.platforms : []
  } catch (e) {
    if (seq !== sourceDetailsSeq) return
    // 接口不可用时保留已有平台代码兜底，不凭空补平台。
    sourceMeta.value = {}
    sourceDetails.value = []
    sourceDetailsLoaded.value = false
  } finally {
    if (seq === sourceDetailsSeq) {
      sourceDetailsLoading.value = false
      if (platformRestorePending) platformRestorePending = false
    }
  }
}

function computePanelPlacement() {
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  const margin = 12
  const gap = 12
  const compact = viewportWidth <= 920
  const baseRight = compact ? 12 : 22
  const baseBottom = compact ? 84 : 22
  const fallbackRect = {
    left: viewportWidth - baseRight - 54 + fabOffset.value.x,
    top: viewportHeight - baseBottom - 54 + fabOffset.value.y,
    right: viewportWidth - baseRight + fabOffset.value.x,
    bottom: viewportHeight - baseBottom + fabOffset.value.y,
    width: 54,
    height: 54
  }
  // 面板展开后悬浮球会隐藏；窗口尺寸变化时用偏移量还原它最后的位置。
  const rect = fab.value?.getBoundingClientRect() || fallbackRect
  if (compact) {
    panelPlacement.value = {
      compact: true,
      mobileSide: rect.left + rect.width / 2 < viewportWidth / 2 ? 'left' : 'right'
    }
    return
  }
  const panelWidth = Math.min(364, viewportWidth - margin * 2)
  const leftSpace = Math.max(0, rect.left - gap - margin)
  const rightSpace = Math.max(0, viewportWidth - rect.right - gap - margin)
  let horizontal = 'left'
  if (leftSpace >= panelWidth && rightSpace < panelWidth) horizontal = 'left'
  else if (rightSpace >= panelWidth && leftSpace < panelWidth) horizontal = 'right'
  else horizontal = leftSpace >= rightSpace ? 'left' : 'right'
  const availableWidth = horizontal === 'left' ? leftSpace : rightSpace
  const finalWidth = Math.min(panelWidth, Math.max(260, availableWidth))
  const topSpace = Math.max(0, rect.top - gap - margin)
  const bottomSpace = Math.max(0, viewportHeight - rect.bottom - gap - margin)
  let vertical = rect.top + rect.height / 2 > viewportHeight / 2 ? 'above' : 'below'
  if (vertical === 'above' && topSpace < 320 && bottomSpace > topSpace) vertical = 'below'
  if (vertical === 'below' && bottomSpace < 320 && topSpace > bottomSpace) vertical = 'above'
  const availableHeight = vertical === 'above' ? topSpace : bottomSpace
  const maxHeight = Math.min(640, Math.max(240, availableHeight))
  panelPlacement.value = {
    compact: false,
    horizontal,
    vertical,
    width: `${Math.round(finalWidth)}px`,
    maxHeight: `${Math.round(maxHeight)}px`,
    right: horizontal === 'left' ? `${Math.round(viewportWidth - rect.left + gap)}px` : 'auto',
    left: horizontal === 'right' ? `${Math.round(rect.right + gap)}px` : 'auto',
    bottom: vertical === 'above' ? `${Math.round(viewportHeight - rect.top + gap)}px` : 'auto',
    top: vertical === 'below' ? `${Math.round(rect.bottom + gap)}px` : 'auto'
  }
}

function openPanel() {
  if (panelOpen.value) return
  if (fabFocusTimer) clearTimeout(fabFocusTimer)
  fabFocusTimer = null
  fabHovered.value = false
  computePanelPlacement()
  const target = resolveInitialView(view.value)
  panelOpen.value = true
  view.value = target
  if (target === 'charts') {
    resetChartScroll()
    loadPlaylists()
  } else if (target === 'ranking') {
    resetChartScroll()
    loadRanking()
  } else if (target === 'playlist') {
    resetChartScroll()
    loadPlaylistDetail()
  } else if (target === 'search') {
    nextTick(() => searchInput.value?.focus())
  } else if (target === 'local') {
    loadLocalTracks()
  } else if (target === 'now') {
    loadLyrics()
  }
}

function closePanel() {
  panelOpen.value = false
  fabHovered.value = false
  platformMenuOpen.value = false
  volumePopoverOpen.value = false
  if (fabFocusTimer) clearTimeout(fabFocusTimer)
  fabFocusTimer = setTimeout(() => {
    fab.value?.focus({ preventScroll: true })
    fabFocusTimer = null
  }, 190)
}

function togglePanel() {
  if (panelOpen.value) closePanel()
  else openPanel()
}

function resetChartScroll() {
  nextTick(() => {
    if (chartBody.value) chartBody.value.scrollTop = 0
  })
}

function showChartsView() {
  if (!hasCharts.value) {
    if (hasRanking.value) {
      showRankingView()
    } else if (hasSearch.value) {
      showSearchView()
    } else if (sourceActive.value) {
      openNowView()
    } else {
      showLocalView()
    }
    return
  }
  view.value = 'charts'
  resetChartScroll()
  loadPlaylists()
}

function showRankingView() {
  if (!hasRanking.value) {
    if (hasCharts.value) showChartsView()
    return
  }
  view.value = 'ranking'
  resetChartScroll()
  loadRanking()
}

function showSearchView() {
  if (!hasSearch.value) return
  view.value = 'search'
  nextTick(() => searchInput.value?.focus())
}

function openNowView(tab = 'lyrics') {
  if (!currentTrack.value) return
  if (!panelOpen.value) openPanel()
  if (view.value !== 'now') lastBrowseView.value = view.value
  view.value = 'now'
  nowTab.value = tab === 'queue' ? 'queue' : 'lyrics'
  platformMenuOpen.value = false
  volumePopoverOpen.value = false
  if (nowTab.value === 'lyrics') {
    loadLyrics()
    nextTick(() => scrollActiveLyric(false))
  }
}

function setNowTab(tab) {
  nowTab.value = tab === 'queue' ? 'queue' : 'lyrics'
  if (nowTab.value === 'lyrics') {
    loadLyrics()
    nextTick(() => scrollActiveLyric(false))
  }
}

function backFromNow() {
  const target = isViewAllowed(lastBrowseView.value) ? lastBrowseView.value : defaultView()
  view.value = target
  if (target === 'charts') loadPlaylists()
  else if (target === 'ranking') loadRanking()
  else if (target === 'playlist') loadPlaylistDetail()
  else if (target === 'search') nextTick(() => searchInput.value?.focus())
  else if (target === 'local') loadLocalTracks(false)
}

function platformMenuTarget(platform) {
  if (menuViewTarget.value === 'ranking' && platform?.charts) return 'ranking'
  return platform?.playlists ? 'charts' : 'ranking'
}

function platformMenuLabel(platform) {
  return platformMenuTarget(platform) === 'ranking' ? '排行榜' : '歌单'
}

function selectPlatformView(code, target) {
  const platform = String(code || '').trim()
  if (!platform) return
  platformMenuOpen.value = false
  platformInitialized = true
  platformRestorePending = false
  const changed = platform !== activePlatform.value
  activePlatform.value = platform
  if (changed) {
    playlistItems.value = []
    playlistTotal.value = 0
    playlistLoadedKey.value = ''
    activePlaylistId.value = ''
    playlistDetail.value = null
    rankingData.value = null
    rankingLoadedKey.value = ''
  }
  if (target === 'ranking') {
    view.value = 'ranking'
    loadRanking(true)
  } else {
    view.value = 'charts'
    loadPlaylists(true)
  }
  resetChartScroll()
}

async function loadLocalTracks(silent = true) {
  if (!silent) localLoading.value = true
  localError.value = ''
  try {
    const res = await http.get('/music/local', { timeout: 20000 })
    localTracks.value = Array.isArray(res.data) ? res.data : []
    localLoaded.value = true
  } catch (e) {
    if (!silent) localError.value = apiErrorMessage(e, '本地音乐暂时不可用')
  } finally {
    if (!silent) localLoading.value = false
  }
}

function showLocalView() {
  if (!localActive.value) {
    if (sourceActive.value) {
      if (hasCharts.value) {
        view.value = 'charts'
      } else if (hasRanking.value) {
        view.value = 'ranking'
      } else if (hasSearch.value) {
        view.value = 'search'
        nextTick(() => searchInput.value?.focus())
      } else {
        openNowView()
      }
    }
    return
  }
  view.value = 'local'
  loadLocalTracks(false)
}

function onLocalMusicChanged() {
  loadLocalTracks(true)
}

function selectLocalTrack(track) {
  if (!tabLeader) {
    requestRemotePlayback('play-track', { track: syncTrackSnapshot(track) })
    return
  }
  const index = addTrackToQueue(track)
  playIndex(index, true)
}

function playAllLocal() {
  if (!localTracks.value.length) return
  if (!tabLeader) {
    requestRemotePlayback('play-collection', { tracks: localTracks.value.map(syncTrackSnapshot) })
    return
  }
  let firstIndex = -1
  for (const track of localTracks.value) {
    const index = addTrackToQueue(track)
    if (firstIndex < 0) firstIndex = index
  }
  if (firstIndex < 0) return
  playIndex(firstIndex, true)
}

function openSearchOrLocal() {
  if (sourceActive.value && hasSearch.value) {
    view.value = 'search'
    nextTick(() => searchInput.value?.focus())
    return
  }
  if (sourceActive.value && hasCharts.value) {
    showChartsView()
    return
  }
  if (sourceActive.value && hasRanking.value) {
    showRankingView()
    return
  }
  if (localActive.value) {
    showLocalView()
    return
  }
  openNowView()
}

function preferredPlatform() {
  const codes = (chartPlatforms.value.length ? chartPlatforms.value : rankingPlatforms.value)
    .map((platform) => platform.code)
  // 音源可能声明了平台但解析通道并不可用。默认优先选择实测最稳定的网易云 / QQ，
  // 其余平台仍按音源声明展示，由用户主动切换。
  if (codes.includes('wy')) return 'wy'
  if (codes.includes('tx')) return 'tx'
  return codes[0] || 'wy'
}

async function loadPlaylists(force = false) {
  if (!sourceActive.value || !hasCharts.value) return
  if (!playlistPlatforms.value.some((item) => item.code === activePlatform.value)) {
    activePlatform.value = preferredPlatform()
  }
  const platform = activePlatform.value
  const key = [sourceUrl.value, platform, 30].join('|')
  if (!force && playlistLoadedKey.value === key && playlistItems.value.length
      && Date.now() - playlistLoadedAt.value < CHART_TTL_MILLIS) {
    return
  }
  const seq = ++playlistSeq.value
  playlistLoading.value = true
  playlistError.value = ''
  try {
    const res = await http.get('/music/playlists', {
      params: { limit: 30, platform },
      timeout: 30000
    })
    if (seq !== playlistSeq.value || platform !== activePlatform.value) return
    const payload = res.data && typeof res.data === 'object' ? res.data : null
    const items = Array.isArray(payload?.items) ? payload.items : []
    playlistItems.value = items.map((item) => ({
      ...item,
      id: String(item?.id || ''),
      platform: String(item?.platform || item?.source || '').toLowerCase(),
      title: String(item?.title || '未命名歌单').trim(),
      cover: String(item?.cover || '').trim(),
      creator: String(item?.creator || '').trim(),
      trackCount: Number(item?.trackCount || 0),
      playCount: Number(item?.playCount || 0)
    })).filter((item) => item.id && item.platform)
    playlistTotal.value = Number(payload?.total || playlistItems.value.length)
    playlistLoadedKey.value = key
    playlistLoadedAt.value = Date.now()
    if (!playlistItems.value.length) playlistError.value = '平台暂未返回歌单，请稍后重试'
    resetChartScroll()
  } catch (e) {
    if (seq !== playlistSeq.value) return
    playlistError.value = apiErrorMessage(e, '歌单暂时不可用，请稍后重试')
  } finally {
    if (seq === playlistSeq.value) playlistLoading.value = false
  }
}

async function loadRanking(force = false) {
  if (!sourceActive.value || !hasRanking.value) return
  if (!rankingPlatforms.value.some((item) => item.code === activePlatform.value)) {
    activePlatform.value = preferredPlatform()
  }
  const platform = activePlatform.value
  const key = [sourceUrl.value, platform, 20].join('|')
  if (!force && rankingLoadedKey.value === key && rankingTracks.value.length
      && Date.now() - rankingLoadedAt.value < CHART_TTL_MILLIS) {
    return
  }
  const seq = ++rankingSeq.value
  rankingLoading.value = true
  rankingError.value = ''
  try {
    const res = await http.get('/music/charts', {
      params: { platform, limit: 20 },
      timeout: 30000
    })
    if (seq !== rankingSeq.value || platform !== activePlatform.value) return
    const payload = res.data && typeof res.data === 'object' ? res.data : null
    if (!payload) throw new Error('排行榜数据为空')
    rankingData.value = {
      ...payload,
      platform,
      chartTitle: String(payload.chartTitle || `${platformDisplayName(platform)}热歌榜`).trim(),
      tracks: Array.isArray(payload.tracks) ? payload.tracks : []
    }
    rankingLoadedKey.value = key
    rankingLoadedAt.value = Date.now()
    resetChartScroll()
  } catch (e) {
    if (seq !== rankingSeq.value) return
    rankingError.value = apiErrorMessage(e, '排行榜暂时不可用，请稍后重试')
  } finally {
    if (seq === rankingSeq.value) rankingLoading.value = false
  }
}

function openPlaylist(playlist) {
  if (!playlist?.id) return
  platformInitialized = true
  platformRestorePending = false
  activePlatform.value = playlist.platform
  activePlaylistId.value = String(playlist.id)
  view.value = 'playlist'
  resetChartScroll()
  loadPlaylistDetail()
}

async function loadPlaylistDetail(force = false) {
  const platform = String(activePlatform.value || '').trim()
  const id = String(activePlaylistId.value || '').trim()
  if (!platform || !id) return
  const cacheKey = platform + ':' + id
  const cached = playlistDetailCache.get(cacheKey)
  if (!force && cached && Date.now() - cached.createdAt < CHART_TTL_MILLIS) {
    playlistDetailSeq.value += 1
    playlistDetail.value = cached.data
    playlistDetailLoading.value = false
    playlistDetailError.value = ''
    resetChartScroll()
    return
  }
  const seq = ++playlistDetailSeq.value
  playlistDetailLoading.value = true
  playlistDetailError.value = ''
  try {
    const res = await http.get('/music/playlist', {
      params: { platform, id },
      timeout: 90000
    })
    if (seq !== playlistDetailSeq.value || platform !== activePlatform.value || id !== activePlaylistId.value) return
    const payload = res.data && typeof res.data === 'object' ? res.data : null
    if (!payload) throw new Error('歌单数据为空')
    const detail = {
      ...payload,
      id: String(payload.id || id),
      platform,
      title: String(payload.title || '').trim(),
      cover: String(payload.cover || '').trim(),
      tracks: Array.isArray(payload.tracks) ? payload.tracks : []
    }
    playlistDetail.value = detail
    playlistDetailCache.set(cacheKey, { createdAt: Date.now(), data: detail })
    resetChartScroll()
  } catch (e) {
    if (seq !== playlistDetailSeq.value) return
    playlistDetailError.value = apiErrorMessage(e, '歌单暂时不可用，请稍后重试')
  } finally {
    if (seq === playlistDetailSeq.value) playlistDetailLoading.value = false
  }
}

function refreshChart() {
  resetChartScroll()
  loadPlaylistDetail(true)
}

function playlistSubtitle(playlist) {
  const label = platformDisplayName(playlist?.platform)
  const count = Number(playlist?.trackCount || 0)
  if (count > 0) return `${label} · ${count} 首`
  return label
}

function addTrackToQueue(track) {
  const source = track.source || 'wy'
  const existingIndex = queue.value.findIndex((item) => item.id === String(track.id) && (item.source || 'wy') === source)
  if (existingIndex >= 0) return existingIndex
  queue.value.push({
    ...track,
    id: String(track.id),
    source,
    url: source === 'local' ? String(track.url || '') : '',
    urlError: '',
    urlLoading: false,
    played: false
  })
  return queue.value.length - 1
}

function isCurrentTrack(track) {
  const current = currentTrack.value
  if (!current || !track) return false
  const currentSource = String(current.source || 'wy')
  const trackSource = String(track.source || 'wy')
  const directMatch = String(current.id) === String(track.id) && currentSource === trackSource
  const originSource = String(current.originSource || '')
  const originId = String(current.originId || '')
  const originMatch = originSource && originId
    && originSource === trackSource
    && originId === String(track.id)
    && String(current.title || '') === String(track.title || '')
  if (!directMatch && !originMatch) return false
  if (currentSource === 'local' && String(current.url || '') !== String(track.url || '')) return false
  return true
}

function isTrackLoading(track) {
  return isCurrentTrack(track) && (loadingTrack.value || Boolean(currentTrack.value?.urlLoading))
}

function isTrackPlaying(track) {
  return isCurrentTrack(track) && playing.value && !loadingTrack.value
}

function toggleChartTrack(track) {
  if (isCurrentTrack(track)) {
    togglePlayback()
    return
  }
  selectChartTrack(track)
}

function toggleLocalTrack(track) {
  if (isCurrentTrack(track)) {
    togglePlayback()
    return
  }
  selectLocalTrack(track)
}

function toggleSearchTrack(track) {
  if (isCurrentTrack(track)) {
    togglePlayback()
    return
  }
  selectSearchResult(track)
}

function toggleQueueItem(index) {
  if (index === currentIndex.value) {
    togglePlayback()
    return
  }
  playIndex(index, true)
}

function selectChartTrack(track) {
  if (!tabLeader) {
    requestRemotePlayback('play-track', { track: syncTrackSnapshot(track) })
    return
  }
  const index = addTrackToQueue(track)
  playIndex(index, true)
}

function playCollection(tracks) {
  if (!tracks.length) return
  if (!tabLeader) {
    requestRemotePlayback('play-collection', { tracks: tracks.map(syncTrackSnapshot) })
    return
  }
  let firstIndex = -1
  for (const track of tracks) {
    const index = addTrackToQueue(track)
    if (firstIndex < 0) firstIndex = index
  }
  if (firstIndex < 0) return
  playIndex(firstIndex, true)
}

function playChartAll() {
  playCollection(activeChartTracks.value)
}

function playRankingAll() {
  playCollection(rankingTracks.value)
}

function loadLayoutState() {
  try {
    const offset = JSON.parse(localStorage.getItem(FAB_OFFSET_KEY) || 'null')
    if (offset && Number.isFinite(Number(offset.x)) && Number.isFinite(Number(offset.y))) {
      fabOffset.value = { x: Number(offset.x), y: Number(offset.y) }
    }
  } catch (e) {
    /* 本地布局状态损坏时使用默认位置 */
  }
}

function persistFabOffset() {
  try {
    localStorage.setItem(FAB_OFFSET_KEY, JSON.stringify(fabOffset.value))
  } catch (e) {
    /* 隐私模式下 localStorage 可能不可用 */
  }
}

function canExpandFab() {
  if (panelOpen.value || fabDrag || !currentTrack.value) return false
  if (window.innerWidth <= 920) return false
  return Boolean(window.matchMedia?.('(hover: hover) and (pointer: fine)').matches)
}

function onFabPointerEnter(event) {
  if (event?.pointerType && event.pointerType !== 'mouse') return
  if (!canExpandFab()) return
  fabHovered.value = true
}

function onFabPointerLeave() {
  fabHovered.value = false
}

function onFabFocusIn() {
  if (fabFocusTimer) return
  if (canExpandFab()) fabHovered.value = true
}

function onFabFocusOut(event) {
  const next = event.relatedTarget
  if (next instanceof Node && event.currentTarget.contains(next)) return
  fabHovered.value = false
}

function beginFabDrag(event) {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  fabHovered.value = true
  const element = event.currentTarget
  const rect = element.getBoundingClientRect()
  const shell = element.closest('.sm-fab-shell')
  const shellRect = shell?.getBoundingClientRect()
  fabDrag = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    offsetX: fabOffset.value.x,
    offsetY: fabOffset.value.y,
    rectLeft: (shellRect?.left ?? rect.left) - fabOffset.value.x,
    rectTop: (shellRect?.top ?? rect.top) - fabOffset.value.y,
    width: shellRect?.width || rect.width,
    height: shellRect?.height || rect.height
  }
  fabDragging.value = true
  element.setPointerCapture?.(event.pointerId)
  window.addEventListener('pointermove', onFabDragMove)
  window.addEventListener('pointerup', endFabDrag, { once: true })
  window.addEventListener('pointercancel', endFabDrag, { once: true })
}

function onFabDragMove(event) {
  if (!fabDrag) return
  const dx = event.clientX - fabDrag.startX
  const dy = event.clientY - fabDrag.startY
  if (!fabMoved.value && Math.abs(dx) + Math.abs(dy) > 4) fabMoved.value = true
  if (!fabMoved.value) return
  const margin = 8
  const minX = margin - fabDrag.rectLeft
  const maxX = window.innerWidth - margin - fabDrag.rectLeft - fabDrag.width
  const minY = margin - fabDrag.rectTop
  const maxY = window.innerHeight - margin - fabDrag.rectTop - fabDrag.height
  fabOffset.value = {
    x: Math.min(maxX, Math.max(minX, fabDrag.offsetX + dx)),
    y: Math.min(maxY, Math.max(minY, fabDrag.offsetY + dy))
  }
}

function endFabDrag() {
  if (!fabDrag) return
  fabDrag = null
  fabDragging.value = false
  window.removeEventListener('pointermove', onFabDragMove)
  window.removeEventListener('pointerup', endFabDrag)
  window.removeEventListener('pointercancel', endFabDrag)
  if (fabMoved.value) {
    persistFabOffset()
    if (fabMoveTimer) clearTimeout(fabMoveTimer)
    fabMoveTimer = setTimeout(() => { fabMoved.value = false }, 260)
  }
}

function onFabClick() {
  if (fabMoved.value) return
  togglePanel()
}

function onViewportResize() {
  compactViewport.value = window.innerWidth <= 920
  if (fabOffset.value.x || fabOffset.value.y) {
    const element = fab.value
    if (element) {
      const rect = element.getBoundingClientRect()
      const baseLeft = rect.left - fabOffset.value.x
      const baseTop = rect.top - fabOffset.value.y
      const margin = 8
      fabOffset.value = {
        x: Math.min(window.innerWidth - margin - baseLeft - rect.width,
          Math.max(margin - baseLeft, fabOffset.value.x)),
        y: Math.min(window.innerHeight - margin - baseTop - rect.height,
          Math.max(margin - baseTop, fabOffset.value.y))
      }
    }
  }
  if (panelOpen.value) computePanelPlacement()
}

function setSearchKeyword(value) {
  searchKeyword.value = value
  scheduleSearch()
}

function scheduleSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  const keyword = searchKeyword.value.trim()
  if (!keyword) {
    searchSeq += 1
    searchLoading.value = false
    searchError.value = ''
    searchResults.value = []
    return
  }
  if (keyword.length < 1) return
  searchTimer = setTimeout(() => runSearch(keyword), 550)
}

async function runSearch(keyword = searchKeyword.value.trim()) {
  const text = String(keyword || '').trim()
  if (!text) return
  searchKeyword.value = text
  view.value = 'search'
  const seq = ++searchSeq
  searchLoading.value = true
  searchError.value = ''
  try {
    const res = await http.get('/music/search', {
      params: { keyword: text, limit: 24 },
      timeout: 30000
    })
    if (seq !== searchSeq) return
    searchResults.value = Array.isArray(res.data?.tracks) ? res.data.tracks : []
    if (!searchResults.value.length) searchError.value = '没有找到相关歌曲，换个关键词试试'
  } catch (e) {
    if (seq !== searchSeq) return
    searchResults.value = []
    searchError.value = apiErrorMessage(e, '搜索暂时不可用，请稍后再试')
  } finally {
    if (seq === searchSeq) searchLoading.value = false
  }
}

function searchSuggestion(keyword) {
  setSearchKeyword(keyword)
  runSearch(keyword)
}

function selectSearchResult(track) {
  if (!tabLeader) {
    requestRemotePlayback('play-track', { track: syncTrackSnapshot(track) })
    return
  }
  const existingIndex = queue.value.findIndex((item) => item.id === track.id && item.source === (track.source || 'wy'))
  let index = existingIndex
  if (index < 0) {
    queue.value.push({
      ...track,
      id: String(track.id),
      source: track.source || 'wy',
      url: '',
      urlError: '',
      urlLoading: false
    })
    index = queue.value.length - 1
  }
  playIndex(index, true)
}

function playIndex(index, autoplay = true) {
  const track = queue.value[index]
  if (!track) return
  if (!tabLeader) {
    requestRemotePlayback('play-track', { track: syncTrackSnapshot(track) })
    return
  }
  track.played = true
  currentIndex.value = index
  progress.value = 0
  duration.value = track.duration || 0
  if (track.url) {
    track.urlRetried = false
    nextTick(() => startAudio(track, autoplay))
    return
  }
  resolveAndPlay(track, autoplay)
}

async function requestPlaybackUrl(lookup, seq, requested = resolveQuality.value) {
  const qualities = requested === '128k' ? ['128k'] : [requested, '128k']
  let lastError = new Error('播放地址为空')
  for (const quality of qualities) {
    try {
      const res = await http.get('/music/url', {
        params: {
          id: String(lookup.id),
          source: lookup.source || 'wy',
          quality,
          title: String(lookup.title || ''),
          artist: String(lookup.artist || ''),
          album: String(lookup.album || ''),
          duration: String(lookup.duration || '')
        },
        timeout: 25000
      })
      if (seq !== playSeq) return null
      const payload = res.data && typeof res.data === 'object' ? res.data : {}
      const url = payload.proxyUrl || payload.url
      if (url) {
        return {
          url,
          quality: payload.quality || quality,
          source: String(payload.source || lookup.source || 'wy'),
          id: String(payload.id || lookup.id || ''),
          fallback: payload.fallback === true,
          platformLabel: String(payload.platformLabel || ''),
          title: payload.title,
          artist: payload.artist,
          album: payload.album,
          cover: payload.cover,
          duration: payload.duration,
          fee: payload.fee,
          pageUrl: payload.pageUrl
        }
      }
      lastError = new Error('播放地址为空')
    } catch (e) {
      lastError = e
    }
  }
  throw lastError
}

function sourceLabelFor(code) {
  const platform = sourcePlatforms.value.find((item) => item.code === code)
  return platform?.label || FALLBACK_PLATFORM_LABELS[code] || String(code || '').toUpperCase()
}

function platformDisplayName(code) {
  return sourceLabelFor(code)
}

async function fetchFallbackCandidates(track) {
  if (!track || track.source === 'local') return []
  try {
    const res = await http.get('/music/fallback', {
      params: {
        title: track.title,
        artist: track.artist || '',
        source: track.source || ''
      },
      timeout: 30000
    })
    return Array.isArray(res.data?.candidates) ? res.data.candidates : []
  } catch (e) {
    return []
  }
}

function applyFallbackCandidate(track, candidate) {
  if (!track.fallbackOriginalSource) track.fallbackOriginalSource = track.source
  if (!track.originSource) {
    track.originSource = track.source
    track.originId = String(track.id || '')
  }
  track.source = String(candidate.source || track.source)
  track.id = String(candidate.id || track.id)
  track.album = candidate.album || track.album || ''
  track.cover = candidate.cover || track.cover || ''
  track.duration = Number(candidate.duration) || track.duration || 0
  track.fee = Number(candidate.fee) || 0
  track.pageUrl = candidate.pageUrl || ''
  track.fallbackLabel = candidate.platformLabel || sourceLabelFor(track.source)
}

async function tryFallbackPlayback(track, autoplay = true, outerSeq = null) {
  if (!track || track.source === 'local') return false
  if (track.fallbackRunning) return false
  const seq = outerSeq == null ? ++playSeq : outerSeq
  if (seq !== playSeq) return false
  track.fallbackRunning = true
  loadingTrack.value = true
  track.urlLoading = true
  track.urlError = ''
  playing.value = false
  if (audio.value) audio.value.pause()
  try {
    if (!Array.isArray(track.fallbackCandidates)) {
      toast('当前来源不可用，正在匹配歌名一致的其他来源…', 'info')
      track.fallbackCandidates = await fetchFallbackCandidates(track)
      track.fallbackIndex = 0
    }
    if (seq !== playSeq || queue.value[currentIndex.value] !== track) return false
    const candidates = track.fallbackCandidates
    if (!candidates.length) {
      track.fallbackMessage = '没有找到歌名和歌手都一致的其他来源'
      return false
    }
    while (Number(track.fallbackIndex || 0) < candidates.length) {
      const candidate = candidates[Number(track.fallbackIndex || 0)]
      track.fallbackIndex = Number(track.fallbackIndex || 0) + 1
      if (!candidate?.id || !candidate?.source) continue
      try {
        const resolved = await requestPlaybackUrl(candidate, seq, playbackQualityFor(candidate))
        if (!resolved || seq !== playSeq || queue.value[currentIndex.value] !== track) return false
        applyFallbackCandidate(track, candidate)
        applyPlaybackResolution(track, resolved)
        track.urlRetried = true
        track.urlLoading = false
        track.urlError = ''
        loadingTrack.value = false
        track.fallbackRunning = false
        await nextTick()
        if (seq !== playSeq || queue.value[currentIndex.value] !== track) return false
        toast(`已切换到${track.fallbackLabel}继续播放`, 'info')
        startAudio(track, autoplay)
        return true
      } catch (e) {
        /* 继续尝试下一个平台的同名歌曲 */
      }
    }
    track.fallbackMessage = '其他来源也没有可用的播放地址'
    return false
  } finally {
    track.fallbackRunning = false
    track.urlLoading = false
    if (seq === playSeq) loadingTrack.value = false
  }
}

function applyPlaybackResolution(track, resolved) {
  if (!track || !resolved) return false
  const nextSource = String(resolved.source || track.source || 'wy').trim()
  const nextId = String(resolved.id || track.id || '').trim()
  const switched = nextSource !== track.source || nextId !== String(track.id || '')
  if (switched) {
    if (!track.fallbackOriginalSource) track.fallbackOriginalSource = track.source
    if (!track.originSource) {
      track.originSource = track.source
      track.originId = String(track.id || '')
    }
    track.source = nextSource
    track.id = nextId
    track.album = resolved.album || track.album || ''
    track.cover = resolved.cover || track.cover || ''
    track.duration = Number(resolved.duration) || track.duration || 0
    track.fee = Number(resolved.fee) || 0
    track.pageUrl = resolved.pageUrl || ''
    track.fallbackLabel = resolved.platformLabel || sourceLabelFor(nextSource)
  }
  track.url = resolved.url
  track.quality = resolved.quality
  return switched
}

async function resolveAndPlay(track, autoplay = true, retrying = false) {
  const seq = ++playSeq
  loadingTrack.value = true
  track.urlLoading = true
  track.urlError = ''
  if (audio.value) {
    audio.value.pause()
  }
  playing.value = false
  try {
    const resolved = await requestPlaybackUrl(track, seq, playbackQualityFor(track))
    if (!resolved || seq !== playSeq) return
    if (queue.value[currentIndex.value] !== track) return
    applyPlaybackResolution(track, resolved)
    track.urlRetried = retrying
    track.urlLoading = false
    loadingTrack.value = false
    if (resolved.fallback) {
      toast(`已自动切换到${resolved.platformLabel || sourceLabelFor(track.source)}播放`, 'info')
    }
    await nextTick()
    if (seq !== playSeq) return
    startAudio(track, autoplay)
  } catch (e) {
    if (seq !== playSeq) return
    // 后端 resolveUrl 已在原平台失败后做过同名换源；这里不再重复请求 /music/fallback，
    // 避免一次失败播放等待两轮跨平台搜索。音频加载失败时仍由 onAudioError 走换源恢复。
    track.urlLoading = false
    loadingTrack.value = false
    track.urlError = apiErrorMessage(e, '该歌曲暂时无法播放')
    playing.value = false
    toast(track.urlError, 'error')
  }
}

const lyricSourceLabel = computed(() => {
  if (lyricProvider.value === 'netease') return '网易云歌词'
  if (lyricProvider.value === 'lrclib') return 'LRCLIB'
  return ''
})

function lyricTrackKey(track) {
  if (!track) return ''
  return [track.source || 'wy', track.id || '', track.title || '', track.artist || ''].join('|')
}

function applyLyricPayload(payload) {
  const rawLines = Array.isArray(payload?.lines) ? payload.lines : []
  const lines = rawLines
    .map((line) => ({
      time: Number(line?.time) || 0,
      text: String(line?.text || '').trim()
    }))
    .filter((line) => line.text)
    .slice(0, 2000)
  lyricLines.value = lines
  lyricHasTimed.value = Boolean(payload?.hasTimed) && lines.some((line) => line.time > 0)
  lyricProvider.value = String(payload?.provider || '').trim()
  lyricError.value = lines.length ? '' : String(payload?.message || '暂无歌词')
  activeLyricIndex.value = -1
  if (lyricHasTimed.value) syncLyricIndex(audio.value?.currentTime || 0)
  else nextTick(() => scrollActiveLyric(true))
}

async function loadLyrics(force = false) {
  const track = currentTrack.value
  if (!track) {
    lyricLines.value = []
    lyricHasTimed.value = false
    lyricError.value = ''
    lyricProvider.value = ''
    activeLyricIndex.value = -1
    return
  }
  if (view.value !== 'now' || nowTab.value !== 'lyrics') return
  const key = lyricTrackKey(track)
  if (!force && lyricLoading.value && lyricRequestKey === key) return
  const cached = lyricCache.get(key)
  if (!force && cached && Date.now() - cached.createdAt < 30 * 60 * 1000) {
    applyLyricPayload(cached.data)
    return
  }
  const seq = ++lyricSeq
  lyricRequestKey = key
  lyricLoading.value = true
  lyricError.value = ''
  lyricLines.value = []
  lyricHasTimed.value = false
  lyricProvider.value = ''
  activeLyricIndex.value = -1
  try {
    const res = await http.get('/music/lyric', {
      params: {
        id: String(track.id || ''),
        source: track.source || 'wy',
        title: String(track.title || ''),
        artist: String(track.artist || ''),
        album: String(track.album || ''),
        duration: String(track.duration || ''),
        refresh: force ? 1 : 0
      },
      timeout: 20000
    })
    if (seq !== lyricSeq || key !== lyricTrackKey(currentTrack.value)) return
    const payload = res.data && typeof res.data === 'object' ? res.data : {}
    lyricCache.set(key, { createdAt: Date.now(), data: payload })
    if (lyricCache.size > 40) lyricCache.clear()
    applyLyricPayload(payload)
  } catch (e) {
    if (seq !== lyricSeq || key !== lyricTrackKey(currentTrack.value)) return
    lyricLines.value = []
    lyricHasTimed.value = false
    lyricProvider.value = ''
    lyricError.value = apiErrorMessage(e, '歌词暂时不可用')
  } finally {
    if (seq === lyricSeq) {
      lyricLoading.value = false
      lyricRequestKey = ''
    }
  }
}

function findLyricIndex(time) {
  const lines = lyricLines.value
  let active = -1
  for (let index = 0; index < lines.length; index += 1) {
    if (Number(lines[index].time) <= time + 0.06) active = index
    else break
  }
  return active
}

function syncLyricIndex(time) {
  if (!lyricHasTimed.value || !lyricLines.value.length) return
  const index = findLyricIndex(Number(time) || 0)
  if (index !== activeLyricIndex.value) {
    activeLyricIndex.value = index
    nextTick(() => scrollActiveLyric())
  }
}

function scrollActiveLyric(reset = false) {
  const container = lyricScroll.value
  if (!container) return
  if (reset || !lyricHasTimed.value || activeLyricIndex.value < 0) {
    container.scrollTo({ top: 0, behavior: 'auto' })
    return
  }
  const active = container.querySelector('.sm-lyric-line.active')
  if (!active) return
  const top = active.offsetTop - container.clientHeight / 2 + active.offsetHeight / 2
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
  container.scrollTo({ top: Math.max(0, top), behavior: reduced ? 'auto' : 'smooth' })
}

function onAudioTimeUpdate() {
  if (!tabLeader) return
  const element = audio.value
  progress.value = element?.currentTime || 0
  syncLyricIndex(progress.value)
  if (Date.now() - lastSyncTickAt >= 800) {
    lastSyncTickAt = Date.now()
    postSyncMessage({
      type: 'tick',
      progress: progress.value,
      duration: currentDuration.value,
      playing: playing.value
    })
  }
}

function seekToTime(time, autoplay = false) {
  const target = Math.max(0, Number(time) || 0)
  if (!tabLeader) {
    progress.value = target
    syncLyricIndex(target)
    requestRemotePlayback('seek-time', { time: target, autoplay })
    return
  }
  const element = audio.value
  if (!element) return
  const max = currentDuration.value || Number(element.duration) || 0
  const next = max > 0 ? Math.min(target, max) : target
  element.currentTime = next
  progress.value = next
  syncLyricIndex(next)
  if (autoplay && element.paused) element.play().catch(() => {})
  lastSyncTickAt = 0
  postSyncMessage({ type: 'tick', progress: progress.value, duration: currentDuration.value, playing: playing.value })
}

function seekToLyric(time) {
  if (!lyricHasTimed.value) return
  seekToTime(time, true)
}

function startAudio(track, autoplay = true) {
  if (!tabLeader) return
  const element = audio.value
  if (!element) return
  element.volume = Math.min(1, Math.max(0, volume.value))
  element.muted = muted.value
  element.load()
  if (autoplay) {
    const attempt = element.play()
    attempt?.catch((error) => {
      if (error?.name === 'AbortError') return
      if (error?.name === 'NotAllowedError') {
        playing.value = false
        toast('浏览器阻止了自动播放，请再点一次播放', 'error')
        return
      }
      onAudioError()
    })
  }
}

function toggleFabPlayback() {
  if (loadingTrack.value) return
  if (!tabLeader) {
    playing.value = !playing.value
    requestRemotePlayback('toggle')
    return
  }
  togglePlayback()
}

function togglePlayback() {
  if (loadingTrack.value) return
  if (!tabLeader) {
    requestRemotePlayback('toggle')
    return
  }
  const track = currentTrack.value
  if (!track) {
    if (queue.value.length) playIndex(0, true)
    else {
      if (!panelOpen.value) openPanel()
      openSearchOrLocal()
    }
    return
  }
  if (!track.url) {
    resolveAndPlay(track, true)
    return
  }
  const element = audio.value
  if (!element) return
  if (element.paused) {
    track.urlRetried = false
    element.play().catch((error) => {
      if (error?.name === 'AbortError') return
      onAudioError()
    })
  } else {
    element.pause()
  }
}

function nextRandomIndex() {
  if (queue.value.length <= 1) return currentIndex.value
  let next = currentIndex.value
  while (next === currentIndex.value) {
    next = Math.floor(Math.random() * queue.value.length)
  }
  return next
}

function step(delta) {
  if (!queue.value.length) return
  if (!tabLeader) {
    requestRemotePlayback('step', { delta })
    return
  }
  if (delta > 0 && mode.value === 'shuffle') {
    playIndex(nextRandomIndex(), true)
    return
  }
  let next = currentIndex.value + delta
  if (next >= queue.value.length) {
    if (mode.value === 'loop') next = 0
    else {
      playing.value = false
      return
    }
  }
  if (next < 0) {
    if (mode.value === 'loop') next = queue.value.length - 1
    else next = 0
  }
  playIndex(next, true)
}

function onEnded() {
  if (!queue.value.length) return
  if (mode.value === 'single') {
    const element = audio.value
    if (element) {
      element.currentTime = 0
      element.play().catch(() => { playing.value = false })
    }
    return
  }
  if (mode.value === 'shuffle') {
    playIndex(nextRandomIndex(), true)
    return
  }
  let next = currentIndex.value + 1
  if (next >= queue.value.length) {
    if (mode.value === 'loop') next = 0
    else {
      playing.value = false
      progress.value = currentDuration.value
      return
    }
  }
  playIndex(next, true)
}

function cycleMode() {
  if (!tabLeader) {
    requestRemotePlayback('cycle-mode')
    return
  }
  const index = MODES.findIndex((item) => item.id === mode.value)
  mode.value = MODES[(index + 1) % MODES.length].id
  toast(`播放模式：${modeMeta.value.label}`, 'info')
}

function seek(event) {
  const percent = Number(event.target.value)
  if (!tabLeader) {
    const estimated = (percent / 100) * currentDuration.value
    progress.value = estimated
    syncLyricIndex(estimated)
    requestRemotePlayback('seek', { percent })
    return
  }
  const element = audio.value
  if (!element || !currentDuration.value) return
  seekToTime((percent / 100) * currentDuration.value)
}

function applyVolumeValue(value) {
  const next = Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0.8
  volume.value = next
  if (next > 0 && muted.value) muted.value = false
  if (audio.value) audio.value.volume = next
}

function setMutedValue(value) {
  muted.value = Boolean(value)
  if (audio.value) audio.value.muted = muted.value
}

function setVolume(event) {
  const next = Number(event.target.value) / 100
  applyVolumeValue(next)
  if (!tabLeader) requestRemotePlayback('set-volume', { value: volume.value })
}

function toggleMute() {
  setMutedValue(!muted.value)
  if (!tabLeader) requestRemotePlayback('set-muted', { muted: muted.value })
}

function removeTrack(index) {
  if (!tabLeader) {
    requestRemotePlayback('remove', { index })
    return
  }
  const wasCurrent = index === currentIndex.value
  const wasPlaying = playing.value
  queue.value.splice(index, 1)
  if (!queue.value.length) {
    currentIndex.value = -1
    playing.value = false
    if (audio.value) audio.value.pause()
    progress.value = 0
    duration.value = 0
    return
  }
  if (index < currentIndex.value) {
    currentIndex.value -= 1
  } else if (wasCurrent) {
    currentIndex.value = Math.min(index, queue.value.length - 1)
    playing.value = false
    if (audio.value) audio.value.pause()
    if (wasPlaying) playIndex(currentIndex.value, true)
  }
}

function clearQueue() {
  if (!tabLeader) {
    requestRemotePlayback('clear')
    return
  }
  queue.value = []
  currentIndex.value = -1
  playing.value = false
  progress.value = 0
  duration.value = 0
  if (audio.value) {
    audio.value.pause()
    audio.value.removeAttribute('src')
  }
}

function resumeLeaderPlayback() {
  const track = currentTrack.value
  const element = audio.value
  if (!track || !element) return
  element.volume = Math.min(1, Math.max(0, volume.value))
  element.muted = muted.value
  if (!track.url) return
  element.load()
  const restore = () => {
    element.removeEventListener('canplay', restore)
    if (!tabLeader) return
    if (Number.isFinite(element.duration) && progress.value > 0) {
      element.currentTime = Math.min(progress.value, element.duration)
    }
    if (playing.value) {
      element.play().catch((error) => {
        if (error?.name === 'AbortError') return
        if (error?.name === 'NotAllowedError') {
          playing.value = false
          sendSyncState()
          return
        }
        onAudioError()
      })
    }
  }
  element.addEventListener('canplay', restore, { once: true })
}

function onAudioPlay() {
  if (!tabLeader) return
  playing.value = true
  scheduleSyncState()
}

function onAudioPause() {
  if (!tabLeader) return
  playing.value = false
  scheduleSyncState()
}

function onAudioLoadedMetadata() {
  if (!tabLeader) return
  duration.value = audio.value?.duration || currentTrack.value?.duration || 0
  scheduleSyncState()
}

function onAudioDurationChange() {
  if (!tabLeader) return
  duration.value = audio.value?.duration || currentTrack.value?.duration || 0
  scheduleSyncState()
}

function onAudioError() {
  if (!tabLeader) return
  const track = currentTrack.value
  if (!track?.url) return
  playing.value = false
  if (track.source === 'local') {
    const message = '本地音频加载失败，请确认文件仍在站内'
    track.urlError = message
    toast(message, 'error')
    return
  }
  if (track.fallbackRunning) return
  track.urlLoading = true
  track.urlError = ''
  tryFallbackPlayback(track, true).then((switched) => {
    if (switched || currentTrack.value !== track) return
    const message = track.fallbackMessage || '音乐无法播放，请检查音源是否可用后重试'
    track.urlLoading = false
    loadingTrack.value = false
    track.urlError = message
    playing.value = false
    toast(message, 'error')
  }).catch(() => {
    if (currentTrack.value !== track) return
    track.urlLoading = false
    loadingTrack.value = false
    track.urlError = '音乐无法播放，请检查音源是否可用后重试'
    toast(track.urlError, 'error')
  })
}

function onKeydown(event) {
  if (event.key !== 'Escape') return
  if (volumePopoverOpen.value) {
    volumePopoverOpen.value = false
    return
  }
  if (platformMenuOpen.value) {
    platformMenuOpen.value = false
    return
  }
  if (panelOpen.value) closePanel()
}

function onDocumentClick(event) {
  if (volumePopoverOpen.value && volumeWrap.value && !volumeWrap.value.contains(event.target)) {
    volumePopoverOpen.value = false
  }
  if (!platformMenuOpen.value) return
  if (platformMenu.value?.contains(event.target)) return
  platformMenuOpen.value = false
}

function onVisibilityChange() {
  if (!tabLeader) return
  if (document.hidden) {
    playing.value = audio.value ? !audio.value.paused : false
  }
}

onMounted(() => {
  loadPersistedState()
  loadLayoutState()
  compactViewport.value = window.innerWidth <= 920
  // 站点配置与音源能力可能在挂载之后才到达，先保留用户选择，等能力加载后再校正，
  // 避免刷新时把平台/视图退回默认值并写回本地存储。
  activePlatform.value = restoredPlatform || preferredPlatform()
  platformRestorePending = Boolean(restoredPlatform) && !sourceDetailsLoaded.value
  activePlaylistId.value = restoredPlaylistId
  if (restoredView === 'now' && currentTrack.value) view.value = 'now'
  else if (['charts', 'ranking', 'search', 'playlist', 'local'].includes(restoredView)) view.value = restoredView
  else view.value = defaultView()
  platformInitialized = true
  loadLocalTracks(true)
  loadSourceCapabilities()
  if (view.value === 'now') nextTick(() => loadLyrics())
  if (audio.value) {
    audio.value.volume = volume.value
    audio.value.muted = muted.value
  }
  initTabSync()
  document.addEventListener('keydown', onKeydown)
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('resize', onViewportResize)
  window.addEventListener('deek:music-local-changed', onLocalMusicChanged)
})

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
  if (fabMoveTimer) clearTimeout(fabMoveTimer)
  if (fabFocusTimer) clearTimeout(fabFocusTimer)
  if (syncStateTimer) clearTimeout(syncStateTimer)
  searchSeq += 1
  playSeq += 1
  playlistSeq.value += 1
  playlistDetailSeq.value += 1
  endFabDrag()
  releaseTabLeadership()
  if (tabChannel) {
    try { tabChannel.close() } catch (e) { /* 已关闭 */ }
    tabChannel = null
  }
  window.removeEventListener('storage', onSyncStorage)
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('resize', onViewportResize)
  window.removeEventListener('deek:music-local-changed', onLocalMusicChanged)
  audio.value?.pause()
})
</script>

<template>
  <div v-if="showPlayer" class="sm-app" :class="{ expanded: panelOpen, playing }">
    <audio
      ref="audio"
      :src="currentTrack?.url || ''"
      preload="metadata"
      @play="onAudioPlay"
      @pause="onAudioPause"
      @timeupdate="onAudioTimeUpdate"
      @loadedmetadata="onAudioLoadedMetadata"
      @durationchange="onAudioDurationChange"
      @ended="onEnded"
      @error="onAudioError"
    ></audio>

    <transition name="sm-panel">
      <aside v-if="panelOpen" class="sm-panel" :style="panelStyle" aria-label="站点音乐播放器">
        <header class="sm-head" title="拖动悬浮球可调整播放器展开方向">
          <div class="sm-brand">
            <span class="sm-brand-mark" aria-hidden="true">
              <svg viewBox="0 0 16 16" fill="currentColor"><circle cx="4" cy="4" r="1.1" /><circle cx="8" cy="4" r="1.1" /><circle cx="12" cy="4" r="1.1" /><circle cx="4" cy="8" r="1.1" /><circle cx="8" cy="8" r="1.1" /><circle cx="12" cy="8" r="1.1" /><circle cx="4" cy="12" r="1.1" /><circle cx="8" cy="12" r="1.1" /><circle cx="12" cy="12" r="1.1" /></svg>
            </span>
            <div>
              <strong>站点音乐</strong>
              <small>{{ sourceActive ? sourceName + (sourceVersion ? ' · v' + sourceVersion : '') : '本地音乐' + (localCount ? ' · ' + localCount + ' 首' : '') }}</small>
            </div>
          </div>
          <div v-if="sourceActive && (hasCharts || hasRanking)" ref="platformMenu" class="sm-menu">
            <button
              type="button"
              class="sm-platform-btn"
              :aria-expanded="platformMenuOpen"
              aria-haspopup="true"
              :title="`${activePlatformLabel} · ${view === 'ranking' ? '排行榜' : '歌单'}；点击切换平台`"
              @click.stop="platformMenuOpen = !platformMenuOpen"
            >
              <i class="sm-platform-dot" :style="{ background: activePlatformInfo?.color || 'var(--accent)' }" aria-hidden="true"></i>
              <span>{{ activePlatformLabel }}</span>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
            </button>
            <div v-if="platformMenuOpen" class="sm-platform-pop" @click.stop>
              <span class="sm-platform-group">{{ menuViewTarget === 'ranking' ? '排行榜' : '推荐歌单' }}</span>
              <button
                v-for="platform in menuPlatforms"
                :key="platform.code"
                type="button"
                class="sm-platform-option"
                :class="{ on: activePlatform === platform.code }"
                @click="selectPlatformView(platform.code, platformMenuTarget(platform))"
              >
                <i class="sm-platform-dot" :style="{ background: platform.color }" aria-hidden="true"></i>
                <span>{{ platformDisplayName(platform.code) }}</span>
                <small>{{ platformMenuLabel(platform) }}</small>
              </button>
            </div>
          </div>
          <button type="button" class="sm-icon-btn sm-close" aria-label="收起播放器" @click="closePanel">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m6 9 6 6 6-6" /></svg>
          </button>
        </header>

        <div v-if="sourceActive && (sourceDetailsLoading || sourceMeta.initAlert)" class="sm-source-meta">
          <span v-if="sourceDetailsLoading" class="sm-source-chip muted">读取音源能力…</span>
          <span v-if="sourceMeta.initAlert" class="sm-source-chip warn">音源提示</span>
        </div>
        <p v-if="sourceActive && sourceMeta.initAlert" class="sm-source-alert">{{ sourceMeta.initAlert }}</p>

        <nav v-if="view !== 'now'" class="sm-tabs" aria-label="播放器视图">
          <button v-if="localActive" type="button" :class="{ on: view === 'local' }" @click="showLocalView">
            本地音乐 <em v-if="localCount">{{ localCount }}</em>
          </button>
          <button v-if="hasCharts" type="button" :class="{ on: view === 'charts' || view === 'playlist' }" @click="showChartsView">歌单</button>
          <button v-if="hasRanking" type="button" :class="{ on: view === 'ranking' }" @click="showRankingView">排行榜</button>
          <button v-if="hasSearch" type="button" :class="{ on: view === 'search' }" @click="showSearchView">搜索</button>
        </nav>

        <div v-if="view === 'local' && localActive" class="sm-body sm-local-body">
          <div class="sm-local-head">
            <div>
              <span class="sm-chart-kicker">LOCAL LIBRARY</span>
              <strong>本地音乐</strong>
              <small>{{ localCount ? `本站已上传 ${localCount} 首，点击即可播放` : '上传到本站的音频会显示在这里' }}</small>
            </div>
            <div class="sm-chart-actions">
              <button type="button" class="sm-text-btn" :disabled="localLoading" @click="loadLocalTracks(false)">刷新</button>
              <button v-if="localTracks.length" type="button" class="sm-chart-playall" @click="playAllLocal">播放全部</button>
            </div>
          </div>

          <div v-if="localPending" class="sm-search-loading">
            <span class="sm-spinner" aria-hidden="true"></span>
            <p>正在读取本地音乐…</p>
          </div>

          <div v-else-if="localError" class="sm-search-error">
            <span>!</span>
            <div>
              <strong>本地音乐暂时不可用</strong>
              <p>{{ localError }}</p>
            </div>
            <button type="button" class="sm-text-btn" @click="loadLocalTracks(false)">重试</button>
          </div>

          <div v-else-if="localTracks.length" class="sm-local-list" role="list" aria-label="本地音乐">
            <button
              v-for="track in localTracks"
              :key="track.id"
              type="button"
              class="sm-local-row"
              role="listitem"
              @click="toggleLocalTrack(track)"
            >
              <span class="sm-local-cover">{{ coverLetter(track) }}</span>
              <span class="sm-local-copy">
                <strong>{{ track.title }}</strong>
                <small>{{ track.artist || '未知歌手' }}<template v-if="track.album"> · {{ track.album }}</template></small>
              </span>
              <span class="sm-local-duration">{{ track.duration ? formatTime(track.duration) : '本地' }}</span>
              <span class="sm-local-play" :class="{ playing: isTrackPlaying(track), loading: isTrackLoading(track) }" aria-hidden="true">
                <span v-if="isTrackLoading(track)" class="sm-list-spinner"></span>
                <svg v-else-if="isTrackPlaying(track)" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 5v14M17 5v14" /></svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 6 9 6-9 6V6Z" /></svg>
              </span>
            </button>
          </div>

          <div v-else class="sm-empty sm-local-empty">
            <span class="sm-empty-orb">♪</span>
            <strong>还没有本地音乐</strong>
            <p>在管理后台“站点音乐 → 本地音乐”上传音频后，会出现在这里。</p>
            <button type="button" class="sm-empty-action" @click="showChartsView" v-if="hasCharts">去听歌单</button>
          </div>
        </div>

        <div v-else-if="view === 'charts' && hasCharts" class="sm-body sm-playlist-body">
          <div class="sm-playlist-head">
            <div>
              <span class="sm-chart-kicker">PLAYLISTS</span>
              <strong>推荐歌单</strong>
              <small v-if="playlistLoading">正在读取 {{ activePlatformLabel }} 歌单…</small>
              <small v-else>{{ activePlatformLabel }} · 点击封面进入歌单</small>
            </div>
          </div>

          <div v-if="playlistLoading && !playlistCards.length" class="sm-playlist-skeleton" aria-hidden="true">
            <span v-for="index in 6" :key="index" class="sm-playlist-skeleton-card">
              <i></i><b></b><u></u>
            </span>
          </div>

          <div v-else-if="playlistError && !playlistCards.length" class="sm-search-error">
            <span>!</span>
            <div>
              <strong>歌单暂时不可用</strong>
              <p>{{ playlistError }}</p>
            </div>
            <button type="button" class="sm-text-btn" @click="loadPlaylists(true)">重试</button>
          </div>

          <div v-else-if="playlistCards.length" class="sm-playlist-grid" role="list" aria-label="推荐歌单">
            <button
              v-for="playlist in playlistCards"
              :key="playlist.platform + '-' + playlist.id"
              type="button"
              class="sm-playlist-card"
              role="listitem"
              :aria-label="`打开歌单 ${playlist.title}`"
              @click="openPlaylist(playlist)"
            >
              <span class="sm-playlist-cover" :style="{ '--sm-playlist-tone': platformColor(playlist.platform) }">
                <img
                  v-if="playlist.cover"
                  :src="playlist.cover"
                  alt=""
                  referrerpolicy="no-referrer"
                  loading="lazy"
                  @error="$event.target.style.display = 'none'"
                />
                <span v-if="!playlist.cover" class="sm-playlist-glyph" aria-hidden="true">{{ sourceLabelFor(playlist.platform).slice(0, 1) }}</span>
                <i class="sm-playlist-shine" aria-hidden="true"></i>
                <span v-if="playlist.trackCount" class="sm-playlist-count">{{ playlist.trackCount }} 首</span>
              </span>
              <span class="sm-playlist-copy">
                <strong>{{ playlist.title }}</strong>
                <small>{{ playlistSubtitle(playlist) }}</small>
              </span>
            </button>
          </div>

          <div v-else class="sm-empty sm-chart-empty">
            <span class="sm-empty-orb">♪</span>
            <strong>音源暂未提供歌单</strong>
            <p>当前音源没有可用的歌单内容，试试直接搜索歌曲。</p>
          </div>
        </div>

        <div v-else-if="view === 'playlist' && hasCharts" ref="chartBody" class="sm-body sm-chart-body">
          <div class="sm-playlist-detail">
            <button type="button" class="sm-playlist-back" @click="showChartsView">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
              歌单
            </button>

            <div class="sm-playlist-hero">
              <div class="sm-playlist-hero-cover" :style="{ '--sm-playlist-tone': activePlaylist?.color || 'var(--accent)' }">
                <img
                  v-if="activePlaylistCover"
                  :src="activePlaylistCover"
                  alt=""
                  referrerpolicy="no-referrer"
                  @error="$event.target.style.display = 'none'"
                />
                <span v-else aria-hidden="true">{{ (activePlaylist?.label || '歌').slice(0, 1) }}</span>
              </div>
              <div class="sm-playlist-hero-copy">
                <span class="sm-chart-kicker">{{ activePlatformLabel }} · PLAYLIST</span>
                <strong>{{ activePlaylistTitle }}</strong>
                <small>{{ activePlaylistSummary }}</small>
              </div>
              <div class="sm-chart-actions">
                <button type="button" class="sm-text-btn" :disabled="playlistDetailLoading" @click="refreshChart">刷新</button>
                <button v-if="activeChartTracks.length" type="button" class="sm-chart-playall" @click="playChartAll">
                  播放全部
                </button>
              </div>
            </div>
          </div>

          <div v-if="playlistDetailLoading" class="sm-search-loading">
            <span class="sm-spinner" aria-hidden="true"></span>
            <p>正在加载{{ activePlaylistTitle }}…</p>
          </div>

          <div v-else-if="playlistDetailError" class="sm-search-error">
            <span>!</span>
            <div>
              <strong>歌单暂时不可用</strong>
              <p>{{ playlistDetailError }}</p>
            </div>
            <button type="button" class="sm-text-btn" @click="refreshChart">重试</button>
          </div>

          <div v-else-if="activeChartTracks.length" class="sm-chart-list" role="list" :aria-label="activePlaylistTitle">
            <button
              v-for="(track, index) in activeChartTracks"
              :key="track.source + '-' + track.id + '-' + index"
              type="button"
              class="sm-chart-row"
              role="listitem"
              @click="toggleChartTrack(track)"
            >
              <span class="sm-chart-rank" :class="{ top: index < 3 }">{{ index + 1 }}</span>
              <span class="sm-chart-cover" :class="{ image: track.cover }">
                <img v-if="track.cover" :src="track.cover" alt="" referrerpolicy="no-referrer" loading="lazy" @error="$event.target.style.display = 'none'" />
                <template v-else>{{ coverLetter(track) }}</template>
              </span>
              <span class="sm-chart-copy">
                <strong>{{ track.title }}</strong>
                <small>{{ track.artist }}<template v-if="track.album"> · {{ track.album }}</template></small>
              </span>
              <span v-if="track.fee > 0" class="sm-vip">VIP</span>
              <span v-else class="sm-chart-duration">{{ track.duration ? formatTime(track.duration) : '' }}</span>
              <span class="sm-chart-play" :class="{ playing: isTrackPlaying(track), loading: isTrackLoading(track) }" aria-hidden="true">
                <span v-if="isTrackLoading(track)" class="sm-list-spinner"></span>
                <svg v-else-if="isTrackPlaying(track)" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 5v14M17 5v14" /></svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 6 9 6-9 6V6Z" /></svg>
              </span>
            </button>
          </div>

          <div v-else class="sm-empty sm-chart-empty">
            <span class="sm-empty-orb">♪</span>
            <strong>这个歌单暂时没有歌曲</strong>
            <p>刷新试试，或返回歌单看看其他内容。</p>
            <button type="button" class="sm-empty-action" @click="showChartsView">返回歌单</button>
          </div>
        </div>

        <div v-else-if="view === 'ranking' && hasRanking" ref="chartBody" class="sm-body sm-ranking-body">
          <div class="sm-ranking-head">
            <div class="sm-ranking-copy">
              <span class="sm-chart-kicker">RANKING</span>
              <strong>{{ rankingTitle }}</strong>
              <small>{{ rankingTracks.length ? `${activePlatformLabel} · ${rankingTracks.length} 首 · 点击歌曲加入队列` : '正在读取排行榜…' }}</small>
            </div>
            <div class="sm-chart-actions">
              <button type="button" class="sm-text-btn" :disabled="rankingLoading" @click="loadRanking(true)">刷新</button>
              <button v-if="rankingTracks.length" type="button" class="sm-chart-playall" @click="playRankingAll">播放全部</button>
            </div>
          </div>

          <div v-if="rankingLoading" class="sm-search-loading">
            <span class="sm-spinner" aria-hidden="true"></span>
            <p>正在读取{{ activePlatformLabel }}排行榜…</p>
          </div>

          <div v-else-if="rankingError" class="sm-search-error">
            <span>!</span>
            <div>
              <strong>排行榜暂时不可用</strong>
              <p>{{ rankingError }}</p>
            </div>
            <button type="button" class="sm-text-btn" @click="loadRanking(true)">重试</button>
          </div>

          <div v-else-if="rankingTracks.length" class="sm-chart-list" role="list" :aria-label="rankingTitle">
            <button
              v-for="(track, index) in rankingTracks"
              :key="track.source + '-' + track.id + '-' + index"
              type="button"
              class="sm-chart-row"
              role="listitem"
              @click="toggleChartTrack(track)"
            >
              <span class="sm-chart-rank" :class="{ top: index < 3 }">{{ index + 1 }}</span>
              <span class="sm-chart-cover" :class="{ image: track.cover }">
                <img v-if="track.cover" :src="track.cover" alt="" referrerpolicy="no-referrer" loading="lazy" @error="$event.target.style.display = 'none'" />
                <template v-else>{{ coverLetter(track) }}</template>
              </span>
              <span class="sm-chart-copy">
                <strong>{{ track.title }}</strong>
                <small>{{ track.artist }}<template v-if="track.album"> · {{ track.album }}</template></small>
              </span>
              <span v-if="track.fee > 0" class="sm-vip">VIP</span>
              <span v-else class="sm-chart-duration">{{ track.duration ? formatTime(track.duration) : '' }}</span>
              <span class="sm-chart-play" :class="{ playing: isTrackPlaying(track), loading: isTrackLoading(track) }" aria-hidden="true">
                <span v-if="isTrackLoading(track)" class="sm-list-spinner"></span>
                <svg v-else-if="isTrackPlaying(track)" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 5v14M17 5v14" /></svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 6 9 6-9 6V6Z" /></svg>
              </span>
            </button>
          </div>

          <div v-else class="sm-empty sm-chart-empty">
            <span class="sm-empty-orb">♪</span>
            <strong>这个平台暂时没有排行榜</strong>
            <p>换个平台，或刷新后再试。</p>
          </div>
        </div>

        <div v-else-if="view === 'now' && currentTrack" class="sm-body sm-nowview-body">
          <div class="sm-nowview-head">
            <button type="button" class="sm-nowview-back" aria-label="返回浏览" @click="backFromNow">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
              <span>返回</span>
            </button>
            <div class="sm-nowview-tabs" role="tablist" aria-label="正在播放功能">
              <button type="button" role="tab" :aria-selected="nowTab === 'lyrics'" :class="{ on: nowTab === 'lyrics' }" @click="setNowTab('lyrics')">歌词</button>
              <button type="button" role="tab" :aria-selected="nowTab === 'queue'" :class="{ on: nowTab === 'queue' }" @click="setNowTab('queue')">播放列表 <em>{{ playedQueue.length }}</em></button>
            </div>
            <span class="sm-nowview-spacer" aria-hidden="true"></span>
          </div>

          <div v-if="nowTab === 'lyrics'" class="sm-nowview-lyrics">
            <div class="sm-nowview-hero">
              <span class="sm-nowview-cover" :class="{ image: currentTrack.cover }">
                <img v-if="currentTrack.cover" :src="currentTrack.cover" alt="" referrerpolicy="no-referrer" @error="$event.target.style.display = 'none'" />
                <template v-else>{{ coverLetter(currentTrack) }}</template>
                <i v-if="playing" class="sm-mini-eq" aria-hidden="true"><b></b><b></b><b></b></i>
              </span>
              <div class="sm-nowview-copy">
                <strong>{{ currentTrack.title }}</strong>
                <small>{{ currentTrack.artist }}<template v-if="currentTrack.album"> · {{ currentTrack.album }}</template></small>
                <span v-if="lyricSourceLabel" class="sm-lyric-source">{{ lyricSourceLabel }}</span>
              </div>
            </div>

            <div ref="lyricScroll" class="sm-lyric-scroll" aria-live="polite">
              <div v-if="lyricLoading" class="sm-lyric-state">
                <span class="sm-list-spinner" aria-hidden="true"></span>
                <strong>正在匹配歌词…</strong>
                <p>歌词按歌名、歌手与平台自动匹配</p>
              </div>
              <div v-else-if="lyricError && !lyricLines.length" class="sm-lyric-state sm-lyric-empty">
                <span aria-hidden="true">♪</span>
                <strong>{{ lyricError === '暂无歌词' ? '这首歌暂时没有歌词' : '歌词暂时不可用' }}</strong>
                <p>{{ lyricError }}</p>
                <button type="button" class="sm-text-btn" @click="loadLyrics(true)">重试</button>
              </div>
              <div v-else-if="lyricLines.length" class="sm-lyric-list" :class="{ 'is-plain': !lyricHasTimed }">
                <button
                  v-for="(line, index) in lyricLines"
                  :key="line.time + '-' + index"
                  type="button"
                  class="sm-lyric-line"
                  :class="{ active: lyricHasTimed && index === activeLyricIndex }"
                  :disabled="!lyricHasTimed"
                  :aria-current="lyricHasTimed && index === activeLyricIndex ? 'true' : undefined"
                  @click="seekToLyric(line.time)"
                >
                  {{ line.text }}
                </button>
              </div>
              <div v-else class="sm-lyric-state sm-lyric-empty">
                <span aria-hidden="true">♪</span>
                <strong>暂无歌词</strong>
                <p>可以继续播放，或换一首歌试试</p>
              </div>
            </div>
          </div>

          <div v-else class="sm-nowview-queue">
            <div class="sm-list-head sm-queue-head">
              <div>
                <span class="sm-chart-kicker">PLAYED</span>
                <strong>播放列表</strong>
                <span>{{ playedQueue.length }} 首</span>
              </div>
              <button v-if="queue.length" type="button" class="sm-text-btn" @click="clearQueue">清空</button>
            </div>

            <div v-if="playedQueue.length" class="sm-track-list" role="list" aria-label="播放列表">
              <div
                v-for="item in playedQueue"
                :key="item.track.id + '-' + item.index"
                class="sm-track"
                :class="{ active: item.index === currentIndex }"
                role="listitem"
              >
                <button type="button" class="sm-track-main" @click="toggleQueueItem(item.index)">
                  <span class="sm-track-cover" :class="{ image: item.track.cover }">
                    <img v-if="item.track.cover" :src="item.track.cover" alt="" referrerpolicy="no-referrer" @error="$event.target.style.display = 'none'" />
                    <template v-else>{{ coverLetter(item.track) }}</template>
                    <i v-if="item.index === currentIndex && playing" class="sm-mini-eq" aria-hidden="true"><b></b><b></b><b></b></i>
                  </span>
                  <span class="sm-track-copy">
                    <strong>{{ item.track.title }}</strong>
                    <small>{{ item.track.artist }}<template v-if="item.track.urlError"> · 播放失败</template></small>
                  </span>
                  <span class="sm-track-duration">{{ item.track.duration ? formatTime(item.track.duration) : '—' }}</span>
                </button>
                <button type="button" class="sm-track-remove" aria-label="从播放列表移除" @click="removeTrack(item.index)">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="m7 7 10 10M17 7 7 17" /></svg>
                </button>
              </div>
            </div>

            <div v-else-if="queue.length" class="sm-empty sm-queue-empty">
              <span class="sm-empty-orb">♪</span>
              <strong>还没有开始播放</strong>
              <p>从歌单、排行榜或搜索结果里选一首歌，播放过的曲目才会出现在这里。</p>
            </div>

            <div v-else class="sm-empty">
              <span class="sm-empty-orb">♪</span>
              <strong>还没有播放音乐</strong>
              <p>{{ hasSource ? '搜索一首歌，或者从歌单里挑一首开始。' : '从本地音乐里挑一首开始播放。' }}</p>
              <button type="button" class="sm-empty-action" @click="openSearchOrLocal">{{ hasSource ? '去搜索' : '浏览本地音乐' }}</button>
            </div>
          </div>
        </div>
        <div v-else class="sm-body sm-search-body">
          <div v-if="hasSearch" class="sm-search sm-search-view">
            <span class="sm-search-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="11" cy="11" r="7" /><path d="m16.5 16.5 4 4" /></svg>
            </span>
            <input
              ref="searchInput"
              :value="searchKeyword"
              type="search"
              enterkeyhint="search"
              autocomplete="off"
              aria-label="搜索歌曲"
              placeholder="搜索歌曲、歌手或专辑"
              @input="setSearchKeyword($event.target.value)"
              @focus="searchFocused = true"
              @blur="searchFocused = false"
              @keydown.enter.prevent="runSearch()"
            />
            <button v-if="searchKeyword" type="button" class="sm-search-clear" aria-label="清空搜索" @click="setSearchKeyword('')">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m7 7 10 10M17 7 7 17" /></svg>
            </button>
            <button type="button" class="sm-search-btn" :disabled="!searchKeyword.trim() || searchLoading" @click="runSearch()">
              {{ searchLoading ? '…' : '搜索' }}
            </button>
          </div>

          <div v-if="searchHintVisible" class="sm-search-hint">
            <span class="sm-search-hint-kicker">SEARCH</span>
            <strong>想听点什么？</strong>
            <p>在{{ searchPlatformSummary }}中搜索歌曲或歌手，播放地址由 {{ sourceName }} 解析。</p>
            <div class="sm-suggestions">
              <button v-for="item in SUGGESTIONS" :key="item" type="button" @click="searchSuggestion(item)">{{ item }}</button>
            </div>
          </div>

          <div v-if="searchLoading" class="sm-search-loading">
            <span class="sm-spinner" aria-hidden="true"></span>
            <p>正在搜索「{{ searchKeyword }}」…</p>
          </div>

          <div v-else-if="searchError" class="sm-search-error">
            <span>!</span>
            <div>
              <strong>没有找到结果</strong>
              <p>{{ searchError }}</p>
            </div>
            <button v-if="searchKeyword" type="button" class="sm-text-btn" @click="runSearch()">重试</button>
          </div>

          <div v-else-if="searchResults.length" class="sm-result-list" role="list" aria-label="搜索结果">
            <div v-for="(track, index) in searchResults" :key="track.id + '-' + index" class="sm-result" role="listitem">
              <button type="button" class="sm-result-main" @click="toggleSearchTrack(track)">
                <span class="sm-result-cover" :class="{ image: track.cover }">
                  <img v-if="track.cover" :src="track.cover" alt="" referrerpolicy="no-referrer" @error="$event.target.style.display = 'none'" loading="lazy" />
                  <template v-else>{{ coverLetter(track) }}</template>
                </span>
                <span class="sm-result-copy">
                  <strong>{{ track.title }}</strong>
                  <small>{{ track.artist }}<template v-if="track.platformLabel"> · <span class="sm-result-platform">{{ track.platformLabel }}</span></template><template v-if="track.album"> · {{ track.album }}</template></small>
                </span>
                <span v-if="track.fee > 0" class="sm-vip">VIP</span>
                <span v-else class="sm-result-duration">{{ track.duration ? formatTime(track.duration) : '' }}</span>
                <span class="sm-result-play" :class="{ playing: isTrackPlaying(track), loading: isTrackLoading(track) }" aria-hidden="true">
                  <span v-if="isTrackLoading(track)" class="sm-list-spinner"></span>
                  <svg v-else-if="isTrackPlaying(track)" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 5v14M17 5v14" /></svg>
                  <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 6 9 6-9 6V6Z" /></svg>
                </span>
              </button>
            </div>
            <p class="sm-result-more">搜索结果来自{{ searchPlatformSummary }} · 点击歌曲加入播放队列</p>
          </div>
        </div>

        <div v-if="currentTrack" class="sm-nowbar">
          <div class="sm-nowbar-progress">
            <span>{{ formatTime(progress) }}</span>
            <input
              type="range"
              min="0"
              max="100"
              step="0.1"
              :value="progressPercent"
              aria-label="播放进度"
              :style="{ '--sm-progress': progressPercent + '%' }"
              @input="seek"
            />
            <span>{{ formatTime(currentDuration) }}</span>
          </div>

          <div class="sm-nowbar-row">
            <button type="button" class="sm-nowbar-track" aria-label="查看歌词与播放列表" @click="openNowView()">
              <span class="sm-nowbar-cover">
                <img v-if="currentTrack.cover" :src="currentTrack.cover" alt="" referrerpolicy="no-referrer" @error="$event.target.style.display = 'none'" />
                <span v-else>{{ coverLetter(currentTrack) }}</span>
                <i v-if="playing" class="sm-mini-eq" aria-hidden="true"><b></b><b></b><b></b></i>
              </span>
              <span class="sm-nowbar-copy">
                <strong>{{ currentTrack.title }}</strong>
                <small>
                  <template v-if="currentTrack.urlLoading">正在获取播放地址…</template>
                  <template v-else-if="currentTrack.urlError">播放失败</template>
                  <template v-else>{{ currentTrack.artist }}<template v-if="currentTrack.album"> · {{ currentTrack.album }}</template></template>
                </small>
              </span>
            </button>

            <div class="sm-nowbar-actions">
              <button type="button" class="sm-dock-mode" :aria-label="'播放模式：' + modeMeta.label" :title="modeMeta.hint" @click="cycleMode">
                <svg v-if="mode === 'shuffle'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 6h3l4.5 6L9 18H6" /><path d="m18 3 3 3-3 3M3 18h3l2-2.7M14 9l4 9h3M18 21l3-3" /></svg>
                <svg v-else-if="mode === 'single'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M17 2l3 3-3 3M20 5H8a5 5 0 0 0-5 5v1M7 22l-3-3 3-3M4 19h12a5 5 0 0 0 5-5v-1" /><path d="M12 10v4M10.7 11.1 12 10l1.3 1.1" /></svg>
                <svg v-else-if="mode === 'sequence'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 6h14M14 2l4 4-4 4M20 18H6M10 14l-4 4 4 4" /></svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M17 2l3 3-3 3M20 5H8a5 5 0 0 0-5 5v1M7 22l-3-3 3-3M4 19h12a5 5 0 0 0 5-5v-1" /></svg>
              </button>
              <button type="button" class="sm-step" aria-label="上一首" @click="step(-1)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 5v14M18 5 9 12l9 7V5Z" /></svg>
              </button>
              <button type="button" class="sm-play" :aria-label="playing ? '暂停' : '播放'" :disabled="loadingTrack" @click="togglePlayback">
                <span v-if="loadingTrack" class="sm-spinner" aria-hidden="true"></span>
                <svg v-else-if="playing" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1"><path d="M7 5v14M17 5v14" /></svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1"><path d="m8 5 11 7-11 7V5Z" /></svg>
              </button>
              <button type="button" class="sm-step" aria-label="下一首" @click="step(1)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M18 5v14M6 5l9 7-9 7V5Z" /></svg>
              </button>
              <div ref="volumeWrap" class="sm-volume-wrap">
                <button
                  type="button"
                  class="sm-volume-toggle"
                  :aria-label="volumePopoverOpen ? '收起音量条' : '调节音量'"
                  :title="volumePopoverOpen ? '收起音量条' : '调节音量'"
                  :aria-expanded="volumePopoverOpen"
                  @click.stop="volumePopoverOpen = !volumePopoverOpen"
                >
                  <svg v-if="muted || volume === 0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 9h4l5-4v14l-5-4H4V9Z" /><path d="m17 9 4 4M21 9l-4 4" /></svg>
                  <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 9h4l5-4v14l-5-4H4V9Z" /><path d="M16 8a5 5 0 0 1 0 8M18.5 5.5a9 9 0 0 1 0 13" /></svg>
                </button>
                <div v-if="volumePopoverOpen" class="sm-volume-pop" role="group" aria-label="音量调节" @click.stop>
                  <strong>{{ volumePercent }}%</strong>
                  <input
                    type="range"
                    min="0"
                    max="100"
                    step="1"
                    orient="vertical"
                    :value="volumePercent"
                    aria-label="音量"
                    :aria-valuetext="volumePercent + '%'"
                    :style="{ '--sm-volume': volumePercent + '%' }"
                    @input="setVolume"
                  />
                  <button type="button" class="sm-volume-mute" :aria-label="muted ? '取消静音' : '静音'" :title="muted ? '取消静音' : '静音'" @click="toggleMute">
                    <svg v-if="muted || volume === 0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 9h4l5-4v14l-5-4H4V9Z" /><path d="m17 9 4 4M21 9l-4 4" /></svg>
                    <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 9h4l5-4v14l-5-4H4V9Z" /><path d="M16 8a5 5 0 0 1 0 8M18.5 5.5a9 9 0 0 1 0 13" /></svg>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <footer v-if="!currentTrack" class="sm-foot">
          <span>{{ footerSourceLabel }}</span>
          <span>{{ sourceActive ? sourceFeatureLabel : '本地音乐 / 播放列表 / 音量' }}</span>
        </footer>
      </aside>
    </transition>

    <div class="sm-fab-slot" :style="fabStyle">
      <transition name="sm-fab">
        <div
          v-if="!panelOpen"
          class="sm-fab-shell"
          :class="{ hovered: fabHovered && currentTrack, dragging: fabDragging, playing }"
          @pointerenter="onFabPointerEnter"
          @pointerleave="onFabPointerLeave"
          @focusin="onFabFocusIn"
          @focusout="onFabFocusOut"
        >
          <button
            ref="fab"
            type="button"
            class="sm-fab"
            aria-label="打开站点音乐"
            title="打开站点音乐；悬停展开播放控制，按住可拖动"
            @pointerdown="beginFabDrag"
            @click="onFabClick"
          >
            <span class="sm-fab-art">
              <img v-if="currentTrack?.cover" :src="currentTrack.cover" alt="" referrerpolicy="no-referrer" />
              <template v-else>♪</template>
            </span>
            <span v-if="playing" class="sm-fab-eq" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
            <span class="sm-fab-ring" aria-hidden="true"></span>
          </button>
          <span v-if="currentTrack" class="sm-fab-info" aria-hidden="true">
            <strong>{{ currentTrack.title }}</strong>
            <small>{{ currentTrack.artist || '未知歌手' }}</small>
          </span>
          <button
            v-if="currentTrack"
            type="button"
            class="sm-fab-play"
            :aria-label="loadingTrack ? '正在加载' : (playing ? '暂停' : '播放')"
            :aria-pressed="playing ? 'true' : 'false'"
            :title="loadingTrack ? '正在加载' : (playing ? '暂停' : '播放')"
            :disabled="loadingTrack"
            @pointerdown.stop
            @click.stop.prevent="toggleFabPlayback"
          >
            <span v-if="loadingTrack" class="sm-spinner" aria-hidden="true"></span>
            <svg v-else-if="playing" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="6" y="5" width="4" height="14" rx="1.2" /><rect x="14" y="5" width="4" height="14" rx="1.2" /></svg>
            <svg v-else viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5.4v13.2L19 12 8 5.4Z" /></svg>
          </button>
        </div>
      </transition>
    </div>
  </div>
</template>

<style scoped>
.sm-app {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 110;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  pointer-events: none;
}

.sm-panel {
  position: fixed;
  right: 22px;
  bottom: 84px;
  display: flex;
  flex-direction: column;
  width: 364px;
  max-width: calc(100vw - 24px);
  max-height: min(80dvh, 640px);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--accent) 20%, var(--line));
  border-radius: 19px;
  background: color-mix(in srgb, var(--paper) 94%, transparent);
  box-shadow: 0 28px 80px rgba(14, 35, 30, .26), inset 0 1px 0 rgba(255, 255, 255, .7);
  backdrop-filter: blur(26px);
  pointer-events: auto;
}

.sm-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 13px 9px;
  border-bottom: 1px solid color-mix(in srgb, var(--line) 66%, transparent);
  user-select: none;
}

.sm-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.sm-brand-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 9px;
  background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 58%, #8d6bff));
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  box-shadow: 0 5px 14px color-mix(in srgb, var(--accent) 24%, transparent);
}
.sm-brand-mark svg { width: 15px; height: 15px; opacity: .92; }
.sm-brand > div { display: grid; gap: 1px; min-width: 0; }
.sm-brand strong { color: var(--ink); font-size: 13px; letter-spacing: .01em; }
.sm-brand small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-menu { position: relative; flex: 0 1 auto; min-width: 0; }
.sm-platform-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 124px;
  min-width: 0;
  padding: 5px 8px;
  border: 1px solid color-mix(in srgb, var(--accent) 20%, var(--line));
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 7%, var(--paper));
  color: var(--accent-ink);
  font-size: 10px;
  font-weight: 650;
  cursor: pointer;
  transition: background .16s ease, border-color .16s ease;
}
.sm-platform-btn:hover { background: color-mix(in srgb, var(--accent) 12%, var(--paper)); border-color: color-mix(in srgb, var(--accent) 34%, var(--line)); }
.sm-platform-btn span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sm-platform-btn svg { width: 13px; height: 13px; flex: 0 0 13px; opacity: .7; }
.sm-platform-dot { width: 6px; height: 6px; flex: 0 0 6px; border-radius: 50%; box-shadow: 0 0 0 2px color-mix(in srgb, currentColor 10%, transparent); }
.sm-platform-pop {
  position: absolute;
  z-index: 30;
  top: calc(100% + 7px);
  right: 0;
  display: grid;
  gap: 2px;
  width: 230px;
  max-height: 460px;
  padding: 7px;
  overflow-y: auto;
  border: 1px solid color-mix(in srgb, var(--line) 72%, transparent);
  border-radius: 12px;
  background: color-mix(in srgb, var(--paper) 94%, #fff);
  box-shadow: 0 18px 46px -18px rgba(15, 35, 30, .32), inset 0 1px 0 rgba(255, 255, 255, .72);
  backdrop-filter: blur(18px);
}
.sm-platform-group {
  padding: 5px 8px 3px;
  color: var(--muted);
  font-size: 8px;
  font-weight: 750;
  letter-spacing: .12em;
}
.sm-platform-group.ranking { margin-top: 5px; border-top: 1px solid color-mix(in srgb, var(--line) 60%, transparent); padding-top: 8px; }
.sm-platform-option {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  min-height: 29px;
  padding: 4px 8px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--ink-soft);
  font-size: 10.5px;
  text-align: left;
  cursor: pointer;
}
.sm-platform-option:hover:not(:disabled) { background: color-mix(in srgb, var(--accent) 6%, var(--paper)); }
.sm-platform-option.on { background: color-mix(in srgb, var(--accent) 10%, var(--paper)); color: var(--accent-ink); font-weight: 650; }
.sm-platform-option:disabled { opacity: .42; cursor: not-allowed; }
.sm-platform-option span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sm-platform-option small { margin-left: auto; color: var(--muted); font-size: 8px; font-weight: 500; }
.sm-platform-option.on small { color: var(--accent-ink); }

.sm-source-pill {
  max-width: 114px;
  overflow: hidden;
  padding: 4px 8px;
  border: 1px solid color-mix(in srgb, var(--accent) 16%, var(--line));
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 6%, var(--paper));
  color: var(--accent-ink);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sm-icon-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--paper);
  color: var(--muted);
  cursor: pointer;
}
.sm-icon-btn:hover { color: var(--ink); border-color: var(--accent); }
.sm-close svg { width: 17px; height: 17px; }

.sm-source-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 8px 15px 0;
  overflow-x: auto;
  scrollbar-width: none;
}
.sm-source-meta::-webkit-scrollbar { display: none; }
.sm-source-chip {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  padding: 3px 8px;
  border: 1px solid color-mix(in srgb, var(--accent) 12%, var(--line));
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 4%, var(--paper));
  color: var(--muted);
  font-size: 9px;
  white-space: nowrap;
}
.sm-source-chip.muted { opacity: .72; }
.sm-source-chip.warn {
  border-color: color-mix(in srgb, #b7791f 28%, var(--line));
  background: color-mix(in srgb, #b7791f 8%, var(--paper));
  color: #946215;
}
[data-theme="dark"] .sm-source-chip.warn { color: #e4b45f; }
.sm-source-alert {
  margin: 6px 15px 0;
  padding: 6px 9px;
  border: 1px solid color-mix(in srgb, #b7791f 22%, var(--line));
  border-radius: 8px;
  background: color-mix(in srgb, #b7791f 6%, var(--paper));
  color: #8a5b12;
  font-size: 10px;
  line-height: 1.5;
}
[data-theme="dark"] .sm-source-alert { color: #e4b45f; }

.sm-search {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 11px 15px 0;
  padding: 0 4px 0 11px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: color-mix(in srgb, var(--paper) 88%, transparent);
  transition: border-color .16s ease, box-shadow .16s ease;
}
.sm-search:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 10%, transparent); }
.sm-search-icon { display: grid; place-items: center; width: 17px; height: 17px; color: var(--muted); flex: 0 0 17px; }
.sm-search-icon svg { width: 17px; height: 17px; }
.sm-search input { flex: 1; min-width: 0; height: 36px; border: 0; outline: 0; background: transparent; color: var(--ink); font-size: 12px; }
.sm-search input::placeholder { color: var(--muted); }
.sm-search input::-webkit-search-cancel-button { display: none; }
.sm-search-clear { display: grid; place-items: center; width: 20px; height: 20px; padding: 0; border: 0; background: transparent; color: var(--muted); cursor: pointer; }
.sm-search-clear svg { width: 14px; height: 14px; }
.sm-search-btn {
  height: 28px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}
.sm-search-btn:disabled { opacity: .45; cursor: default; }

.sm-tabs {
  display: flex;
  gap: 2px;
  margin: 8px 13px 0;
  padding: 3px;
  border: 1px solid color-mix(in srgb, var(--line) 70%, transparent);
  border-radius: 9px;
  background: color-mix(in srgb, var(--accent) 4%, var(--paper));
}
.sm-tabs button {
  flex: 1;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 10px;
  font-weight: 600;
  cursor: pointer;
}
.sm-tabs button.on { background: var(--paper); color: var(--ink); box-shadow: 0 3px 10px rgba(17, 24, 39, .06); }
.sm-tabs em { color: var(--accent-ink); font-size: 8.5px; font-style: normal; }

.sm-chart-body { display: flex; flex-direction: column; gap: 9px; }
.sm-ranking-body { display: flex; flex-direction: column; gap: 9px; }
.sm-ranking-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 10px; padding: 2px 2px 0; }
.sm-ranking-copy { display: grid; gap: 2px; min-width: 0; }
.sm-ranking-copy strong { overflow: hidden; color: var(--ink); font-size: 17px; letter-spacing: -.03em; text-overflow: ellipsis; white-space: nowrap; }
.sm-ranking-copy small { color: var(--muted); font-size: 9px; line-height: 1.5; }
.sm-search-view { margin: 0; }
.sm-search-body { display: flex; flex-direction: column; gap: 10px; }
.sm-playlist-body { display: flex; flex-direction: column; gap: 10px; }
.sm-playlist-head { display: grid; gap: 2px; padding: 2px 2px 0; }
.sm-playlist-head > div { display: grid; gap: 2px; min-width: 0; }
.sm-playlist-head strong { color: var(--ink); font-size: 18px; letter-spacing: -.03em; }
.sm-playlist-head small { color: var(--muted); font-size: 9px; line-height: 1.5; }
.sm-chart-kicker { color: var(--accent-ink); font-size: 8px; font-weight: 800; letter-spacing: .13em; }

.sm-playlist-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 13px 9px;
  padding-bottom: 4px;
}
.sm-playlist-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}
.sm-playlist-card:hover .sm-playlist-cover {
  transform: translateY(-2px);
  box-shadow: 0 14px 26px -18px color-mix(in srgb, var(--accent) 60%, transparent);
}
.sm-playlist-cover {
  position: relative;
  display: flex;
  align-items: flex-end;
  width: 100%;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: 10px;
  background:
    radial-gradient(120% 90% at 82% 8%, color-mix(in srgb, var(--sm-playlist-tone) 34%, #fff) 0 12%, transparent 52%),
    linear-gradient(135deg, color-mix(in srgb, var(--sm-playlist-tone) 72%, #0a1d36), color-mix(in srgb, var(--sm-playlist-tone) 54%, #dce9ff));
  transition: transform .18s ease, box-shadow .18s ease;
}
.sm-playlist-cover img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.sm-playlist-cover::after {
  content: "";
  position: absolute;
  inset: 25% 0 0;
  background: linear-gradient(180deg, transparent, rgba(4, 16, 34, .58));
  pointer-events: none;
}
.sm-playlist-glyph {
  position: relative;
  z-index: 1;
  margin: 0 0 3px 9px;
  color: rgba(255, 255, 255, .78);
  font-size: 28px;
  font-weight: 800;
  line-height: 1;
  text-shadow: 0 2px 12px rgba(0, 0, 0, .22);
}
.sm-playlist-shine {
  position: absolute;
  top: -24%;
  left: -36%;
  width: 46%;
  height: 150%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, .2), transparent);
  transform: rotate(18deg);
  pointer-events: none;
}
.sm-playlist-count {
  position: absolute;
  z-index: 1;
  left: 8px;
  bottom: 7px;
  padding: 2px 6px;
  border-radius: 999px;
  background: rgba(5, 18, 38, .42);
  color: #fff;
  font-size: 8px;
  font-weight: 650;
  backdrop-filter: blur(6px);
}
.sm-playlist-arrow {
  position: absolute;
  z-index: 2;
  top: 13px;
  right: 13px;
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border: 1px solid rgba(255, 255, 255, .32);
  border-radius: 50%;
  background: rgba(5, 18, 38, .24);
  color: #fff;
  font-size: 11px;
  backdrop-filter: blur(6px);
}
.sm-playlist-copy { display: grid; gap: 3px; min-width: 0; padding: 0 1px; }
.sm-playlist-copy strong {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink-soft);
  font-size: 11px;
  font-weight: 650;
  line-height: 1.35;
  letter-spacing: -.01em;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.sm-playlist-copy small { overflow: hidden; color: var(--muted); font-size: 8.5px; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }

.sm-playlist-skeleton { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 13px 9px; }
.sm-playlist-skeleton-card { display: grid; gap: 6px; }
.sm-playlist-skeleton-card i {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  border-radius: 10px;
  background: linear-gradient(100deg, color-mix(in srgb, var(--line) 55%, transparent) 20%, color-mix(in srgb, var(--paper) 70%, transparent) 40%, color-mix(in srgb, var(--line) 55%, transparent) 60%);
  background-size: 240% 100%;
  animation: sm-skeleton 1.35s ease-in-out infinite;
}
.sm-playlist-skeleton-card b,
.sm-playlist-skeleton-card u {
  display: block;
  height: 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--line) 48%, transparent);
}
.sm-playlist-skeleton-card b { width: 86%; }
.sm-playlist-skeleton-card u { width: 54%; height: 7px; }
@keyframes sm-skeleton {
  0% { background-position: 110% 0; }
  100% { background-position: -110% 0; }
}

.sm-playlist-detail { display: grid; gap: 8px; flex: 0 0 auto; }
.sm-playlist-back {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  gap: 3px;
  padding: 2px 3px 2px 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 9.5px;
  cursor: pointer;
}
.sm-playlist-back svg { width: 12px; height: 12px; }
.sm-playlist-back:hover { color: var(--accent-ink); }
.sm-playlist-hero {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 8px;
  border: 1px solid color-mix(in srgb, var(--accent) 13%, var(--line));
  border-radius: 13px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 7%, var(--paper)), var(--paper));
}
.sm-playlist-hero-cover {
  position: relative;
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  flex: 0 0 58px;
  overflow: hidden;
  border-radius: 11px;
  background: linear-gradient(145deg, color-mix(in srgb, var(--sm-playlist-tone) 72%, #0a1d36), color-mix(in srgb, var(--sm-playlist-tone) 52%, #e2ecff));
  color: #fff;
  font-size: 23px;
  font-weight: 800;
  box-shadow: 0 8px 18px -14px color-mix(in srgb, var(--sm-playlist-tone) 55%, transparent);
}
.sm-playlist-hero-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-playlist-hero-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-playlist-hero-copy strong { overflow: hidden; color: var(--ink); font-size: 15px; letter-spacing: -.02em; text-overflow: ellipsis; white-space: nowrap; }
.sm-playlist-hero-copy small { overflow: hidden; color: var(--muted); font-size: 8.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-chart-actions { display: flex; align-items: center; gap: 7px; flex: 0 0 auto; }
.sm-chart-actions .sm-text-btn:disabled { opacity: .45; cursor: default; text-decoration: none; }
.sm-chart-playall {
  padding: 5px 9px;
  border: 1px solid color-mix(in srgb, var(--accent) 24%, var(--line));
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 8%, var(--paper));
  color: var(--accent-ink);
  font-size: 9px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}
.sm-chart-playall:hover { background: var(--accent); color: #fff; border-color: var(--accent); }

.sm-chart-list { display: flex; flex-direction: column; gap: 1px; padding-bottom: 3px; }
.sm-chart-row {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 5px 4px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}
.sm-chart-row:hover { background: color-mix(in srgb, var(--accent) 6%, transparent); }
.sm-chart-row:hover .sm-chart-play { background: var(--accent); border-color: var(--accent); color: #fff; }
.sm-chart-rank { width: 18px; flex: 0 0 18px; color: var(--muted); font-size: 10px; font-variant-numeric: tabular-nums; font-weight: 700; text-align: center; }
.sm-chart-rank.top { color: var(--accent-ink); }
.sm-chart-cover { display: grid; place-items: center; width: 36px; height: 36px; flex: 0 0 36px; overflow: hidden; border-radius: 8px; background: color-mix(in srgb, var(--accent) 12%, var(--paper)); color: var(--accent-ink); font-size: 13px; font-weight: 700; }
.sm-chart-cover.image { background: var(--paper); }
.sm-chart-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-chart-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-chart-copy strong { overflow: hidden; color: var(--ink-soft); font-size: 11px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.sm-chart-copy small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-chart-duration { flex: 0 0 auto; color: var(--muted); font-size: 8.5px; font-variant-numeric: tabular-nums; }
.sm-chart-play { display: grid; place-items: center; width: 24px; height: 24px; flex: 0 0 24px; border: 1px solid var(--line); border-radius: 50%; color: var(--muted); transition: background .16s ease, color .16s ease, border-color .16s ease; }
.sm-chart-play svg { width: 13px; height: 13px; }
.sm-chart-play.playing, .sm-local-play.playing, .sm-result-play.playing { background: var(--accent); border-color: var(--accent); color: #fff; }
.sm-list-spinner { display: block; box-sizing: border-box; width: 12px; height: 12px; flex: 0 0 12px; border: 1.5px solid color-mix(in srgb, currentColor 18%, transparent); border-top-color: currentColor; border-radius: 50%; animation: sm-spin .7s linear infinite; }
.sm-chart-play.loading, .sm-local-play.loading, .sm-result-play.loading { background: var(--accent); border-color: var(--accent); color: #fff; animation: sm-loader-ring 1.4s ease-out infinite; }
.sm-chart-play.loading .sm-list-spinner, .sm-local-play.loading .sm-list-spinner, .sm-result-play.loading .sm-list-spinner {
  width: 15px; height: 15px; flex-basis: 15px;
  border: 2px solid rgba(255, 255, 255, .32); border-top-color: #fff; border-left-color: rgba(255, 255, 255, .72);
  box-shadow: 0 0 2px rgba(255, 255, 255, .24);
}
.sm-chart-empty { padding-top: 20px; }

.sm-local-body { display: flex; flex-direction: column; gap: 10px; }
.sm-local-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 10px; flex: 0 0 auto; padding: 2px 2px 0; }
.sm-local-head > div:first-child { display: grid; gap: 2px; min-width: 0; }
.sm-local-head strong { overflow: hidden; color: var(--ink); font-size: 16px; letter-spacing: -.02em; text-overflow: ellipsis; white-space: nowrap; }
.sm-local-head small { overflow: hidden; color: var(--muted); font-size: 8.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-local-list { display: flex; flex-direction: column; gap: 2px; padding-bottom: 3px; }
.sm-local-row { display: flex; align-items: center; gap: 8px; width: 100%; padding: 6px 5px; border: 0; border-radius: 10px; background: transparent; color: var(--ink); text-align: left; cursor: pointer; }
.sm-local-row:hover { background: color-mix(in srgb, var(--accent) 6%, transparent); }
.sm-local-row:hover .sm-local-play { border-color: var(--accent); background: var(--accent); color: #fff; }
.sm-local-cover { display: grid; place-items: center; width: 36px; height: 36px; flex: 0 0 36px; border-radius: 9px; background: linear-gradient(145deg, color-mix(in srgb, var(--accent) 18%, var(--paper)), color-mix(in srgb, #8d6bff 12%, var(--paper))); color: var(--accent-ink); font-size: 13px; font-weight: 700; }
.sm-local-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-local-copy strong { overflow: hidden; color: var(--ink-soft); font-size: 11.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.sm-local-copy small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-local-duration { flex: 0 0 auto; color: var(--muted); font-size: 8.5px; font-variant-numeric: tabular-nums; }
.sm-local-play { display: grid; place-items: center; width: 24px; height: 24px; flex: 0 0 24px; border: 1px solid var(--line); border-radius: 50%; color: var(--muted); transition: background .16s ease, color .16s ease, border-color .16s ease; }
.sm-local-play svg { width: 13px; height: 13px; }
.sm-local-empty { padding-top: 18px; }

.sm-body { min-height: 0; flex: 1; overflow-y: auto; padding: 10px 13px 4px; scrollbar-width: thin; }
.sm-queue-body { display: flex; flex-direction: column; gap: 11px; }
.sm-now { display: flex; align-items: center; gap: 11px; min-width: 0; padding: 11px; border: 1px solid color-mix(in srgb, var(--accent) 13%, var(--line)); border-radius: 13px; background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 8%, var(--paper)), var(--paper)); }
.sm-now-cover { position: relative; display: grid; place-items: center; width: 54px; height: 54px; flex: 0 0 54px; overflow: hidden; border-radius: 12px; background: linear-gradient(145deg, var(--accent), #8d6bff); color: #fff; font-size: 20px; font-weight: 800; box-shadow: 0 7px 16px color-mix(in srgb, var(--accent) 20%, transparent); }
.sm-now-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-now-playing { position: absolute; right: 4px; bottom: 4px; display: flex; align-items: flex-end; gap: 2px; width: 17px; height: 17px; padding: 3px; border-radius: 50%; background: rgba(0, 0, 0, .55); }
.sm-now-playing b { width: 2px; border-radius: 2px; background: #fff; animation: sm-bars .9s ease-in-out infinite alternate; }
.sm-now-playing b:nth-child(1) { height: 5px; }
.sm-now-playing b:nth-child(2) { height: 9px; animation-delay: .16s; }
.sm-now-playing b:nth-child(3) { height: 7px; animation-delay: .3s; }
.sm-now-copy { display: grid; gap: 3px; min-width: 0; flex: 1; }
.sm-now-copy strong { overflow: hidden; color: var(--ink); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.sm-now-copy span { overflow: hidden; color: var(--ink-soft); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-now-copy small { overflow: hidden; color: var(--muted); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-now-copy small.danger { color: #c94a4a; }
.sm-vip { flex: 0 0 auto; padding: 2px 5px; border-radius: 5px; background: color-mix(in srgb, #d4942a 12%, var(--paper)); color: #b0740e; font-size: 8px; font-weight: 800; letter-spacing: .04em; }

.sm-progress { display: grid; gap: 3px; }
.sm-progress input, .sm-volume input { width: 100%; height: 16px; margin: 0; appearance: none; -webkit-appearance: none; background: transparent; cursor: pointer; }
.sm-progress input::-webkit-slider-runnable-track { height: 4px; border-radius: 999px; background: linear-gradient(90deg, var(--accent) var(--sm-progress), color-mix(in srgb, var(--muted) 16%, var(--paper)) var(--sm-progress)); }
.sm-progress input::-moz-range-track { height: 4px; border-radius: 999px; background: color-mix(in srgb, var(--muted) 16%, var(--paper)); }
.sm-progress input::-webkit-slider-thumb { width: 13px; height: 13px; margin-top: -4.5px; appearance: none; border: 2px solid var(--paper); border-radius: 50%; background: var(--accent); box-shadow: 0 1px 4px rgba(0, 0, 0, .22); }
.sm-progress input::-moz-range-thumb { width: 11px; height: 11px; border: 2px solid var(--paper); border-radius: 50%; background: var(--accent); }
.sm-time-row { display: flex; justify-content: space-between; color: var(--muted); font-size: 9px; font-variant-numeric: tabular-nums; }

.sm-controls { display: flex; align-items: center; justify-content: space-between; gap: 4px; }
.sm-mode { display: inline-flex; align-items: center; gap: 4px; min-width: 68px; padding: 5px 7px; border: 1px solid transparent; border-radius: 7px; background: transparent; color: var(--muted); font-size: 9px; cursor: pointer; white-space: nowrap; }
.sm-mode:hover { border-color: var(--line); color: var(--ink); }
.sm-mode svg { width: 15px; height: 15px; }
.sm-step { display: grid; place-items: center; width: 36px; height: 36px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--ink-soft); cursor: pointer; }
.sm-step:hover { background: var(--accent-soft); color: var(--ink); }
.sm-step svg { width: 18px; height: 18px; }
.sm-play { display: grid; place-items: center; width: 46px; height: 46px; flex: 0 0 46px; padding: 0; border: 0; border-radius: 50%; background: var(--accent); color: #fff; box-shadow: 0 7px 18px color-mix(in srgb, var(--accent) 27%, transparent); cursor: pointer; }
.sm-play:hover { transform: scale(1.04); }
.sm-play:disabled { opacity: .7; cursor: wait; }
.sm-play svg { width: 20px; height: 20px; }
.sm-volume-toggle { display: grid; place-items: center; width: 30px; height: 30px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--muted); cursor: pointer; }
.sm-volume-toggle:hover { color: var(--ink); }
.sm-volume-toggle svg { width: 17px; height: 17px; }
.sm-spinner { display: block; width: 18px; height: 18px; border: 2px solid rgba(255, 255, 255, .28); border-top-color: #fff; border-radius: 50%; animation: sm-spin .7s linear infinite; }

.sm-volume { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 9px; }
.sm-volume span { flex: 0 0 38px; }
.sm-volume input { flex: 1; height: 14px; }
.sm-volume input::-webkit-slider-runnable-track { height: 3px; border-radius: 999px; background: linear-gradient(90deg, var(--accent) var(--sm-volume), color-mix(in srgb, var(--muted) 16%, var(--paper)) var(--sm-volume)); }
.sm-volume input::-moz-range-track { height: 3px; border-radius: 999px; background: color-mix(in srgb, var(--muted) 16%, var(--paper)); }
.sm-volume input::-webkit-slider-thumb { width: 10px; height: 10px; margin-top: -3.5px; appearance: none; border-radius: 50%; background: var(--accent); }
.sm-volume input::-moz-range-thumb { width: 8px; height: 8px; border: 0; border-radius: 50%; background: var(--accent); }
.sm-volume strong { min-width: 22px; color: var(--ink-soft); font-size: 9px; font-variant-numeric: tabular-nums; text-align: right; }

.sm-nowbar {
  position: relative;
  z-index: 4;
  flex: 0 0 auto;
  padding: 6px 13px 8px;
  border-top: 1px solid color-mix(in srgb, var(--accent) 14%, var(--line));
  background: color-mix(in srgb, var(--paper) 95%, transparent);
  box-shadow: 0 -10px 24px color-mix(in srgb, var(--ink) 7%, transparent);
}
.sm-nowbar-progress { display: flex; align-items: center; gap: 6px; }
.sm-nowbar-progress span { flex: 0 0 30px; color: var(--muted); font-size: 7.5px; font-variant-numeric: tabular-nums; }
.sm-nowbar-progress span:last-child { text-align: right; }
.sm-nowbar-progress input { flex: 1; height: 13px; margin: 0; appearance: none; -webkit-appearance: none; background: transparent; cursor: pointer; }
.sm-nowbar-progress input::-webkit-slider-runnable-track { height: 3px; border-radius: 999px; background: linear-gradient(90deg, var(--accent) var(--sm-progress), color-mix(in srgb, var(--muted) 16%, var(--paper)) var(--sm-progress)); }
.sm-nowbar-progress input::-moz-range-track { height: 3px; border-radius: 999px; background: color-mix(in srgb, var(--muted) 16%, var(--paper)); }
.sm-nowbar-progress input::-webkit-slider-thumb { width: 10px; height: 10px; margin-top: -3.5px; appearance: none; border: 1.5px solid var(--paper); border-radius: 50%; background: var(--accent); }
.sm-nowbar-progress input::-moz-range-thumb { width: 9px; height: 9px; border: 1.5px solid var(--paper); border-radius: 50%; background: var(--accent); }
.sm-nowbar-row { display: flex; align-items: center; gap: 5px; min-width: 0; margin-top: 2px; }
.sm-nowbar-track { display: flex; align-items: center; gap: 7px; min-width: 0; flex: 1; padding: 4px 8px 4px 4px; border: 1px solid color-mix(in srgb, var(--accent) 14%, var(--line)); border-radius: 999px; background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 7%, var(--paper)), var(--paper)); color: var(--ink); text-align: left; cursor: pointer; }
.sm-nowbar-track:hover { border-color: color-mix(in srgb, var(--accent) 32%, var(--line)); }
.sm-nowbar-cover { position: relative; display: grid; place-items: center; width: 32px; height: 32px; flex: 0 0 32px; overflow: hidden; border-radius: 8px; background: linear-gradient(145deg, var(--accent), #8d6bff); color: #fff; font-size: 13px; font-weight: 800; }
.sm-nowbar-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-nowbar-copy { display: grid; gap: 1px; min-width: 0; flex: 1; }
.sm-nowbar-copy strong { overflow: hidden; color: var(--ink); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-nowbar-copy small { overflow: hidden; color: var(--muted); font-size: 8.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-nowbar-actions { display: flex; align-items: center; gap: 0; flex: 0 0 auto; }
.sm-dock-mode { display: grid; place-items: center; width: 25px; height: 28px; padding: 0; border: 0; border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; }
.sm-dock-mode:hover { background: var(--accent-soft); color: var(--ink); }
.sm-dock-mode svg { width: 14px; height: 14px; }
.sm-nowbar .sm-step { width: 28px; height: 28px; }
.sm-nowbar .sm-step svg { width: 15px; height: 15px; }
.sm-nowbar .sm-play { width: 36px; height: 36px; flex-basis: 36px; box-shadow: 0 5px 14px color-mix(in srgb, var(--accent) 24%, transparent); }
.sm-nowbar .sm-play svg { width: 17px; height: 17px; }
.sm-nowbar .sm-spinner { width: 14px; height: 14px; border-width: 1.5px; }
.sm-nowbar .sm-volume-toggle { width: 28px; height: 28px; }
.sm-nowbar .sm-volume-toggle svg { width: 16px; height: 16px; }
.sm-volume-wrap { position: relative; display: flex; align-items: center; }
.sm-volume-pop {
  position: absolute;
  right: 50%;
  bottom: calc(100% + 7px);
  transform: translateX(50%);
  z-index: 30;
  display: grid;
  justify-items: center;
  gap: 7px;
  width: 46px;
  padding: 8px 7px 7px;
  border: 1px solid color-mix(in srgb, var(--accent) 18%, var(--line));
  border-radius: 12px;
  background: var(--paper);
  box-shadow: 0 12px 32px rgba(14, 35, 30, .2);
}
.sm-volume-pop strong { color: var(--ink-soft); font-size: 8.5px; font-weight: 600; font-variant-numeric: tabular-nums; }
.sm-volume-pop input {
  width: 18px;
  height: 92px;
  margin: 0;
  appearance: none;
  -webkit-appearance: none;
  background: transparent;
  writing-mode: vertical-lr;
  direction: rtl;
  cursor: pointer;
}
.sm-volume-pop input::-webkit-slider-runnable-track { width: 4px; height: 92px; border-radius: 999px; background: linear-gradient(to top, var(--accent) var(--sm-volume), color-mix(in srgb, var(--muted) 16%, var(--paper)) var(--sm-volume)); }
.sm-volume-pop input::-moz-range-track { width: 4px; height: 92px; border-radius: 999px; background: color-mix(in srgb, var(--muted) 16%, var(--paper)); }
.sm-volume-pop input::-moz-range-progress { width: 4px; border-radius: 999px; background: var(--accent); }
.sm-volume-pop input::-webkit-slider-thumb { width: 12px; height: 12px; appearance: none; border: 2px solid var(--paper); border-radius: 50%; background: var(--accent); box-shadow: 0 1px 5px rgba(0, 0, 0, .2); transform: translateX(-4px); }
.sm-volume-pop input::-moz-range-thumb { width: 11px; height: 11px; border: 2px solid var(--paper); border-radius: 50%; background: var(--accent); }
.sm-volume-mute { display: grid; place-items: center; width: 23px; height: 23px; padding: 0; border: 0; border-radius: 50%; background: color-mix(in srgb, var(--accent) 7%, var(--paper)); color: var(--muted); cursor: pointer; }
.sm-volume-mute:hover { color: var(--ink); }
.sm-volume-mute svg { width: 14px; height: 14px; }

.sm-list-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 2px; padding: 4px 1px 0; border-top: 1px solid color-mix(in srgb, var(--line) 60%, transparent); }
.sm-queue-head { margin-top: 0; padding-top: 0; border-top: 0; }
.sm-list-head > div { display: flex; align-items: baseline; gap: 6px; }
.sm-list-head strong { color: var(--ink); font-size: 11px; }
.sm-list-head span { color: var(--muted); font-size: 9px; }
.sm-text-btn { padding: 2px 0; border: 0; background: transparent; color: var(--accent-ink); font-size: 9px; cursor: pointer; }
.sm-text-btn:hover { text-decoration: underline; }

.sm-track-list { display: flex; flex-direction: column; gap: 1px; padding-bottom: 3px; }
.sm-track { display: flex; align-items: center; gap: 2px; border-radius: 9px; }
.sm-track.active { background: color-mix(in srgb, var(--accent) 7%, var(--paper)); }
.sm-track-main { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; padding: 6px 4px; border: 0; border-radius: 9px; background: transparent; color: var(--ink); text-align: left; cursor: pointer; }
.sm-track-main:hover { background: color-mix(in srgb, var(--accent) 5%, transparent); }
.sm-track-cover { position: relative; display: grid; place-items: center; width: 31px; height: 31px; flex: 0 0 31px; overflow: hidden; border-radius: 7px; background: color-mix(in srgb, var(--accent) 12%, var(--paper)); color: var(--accent-ink); font-size: 12px; font-weight: 700; }
.sm-track-cover.image { background: var(--paper); }
.sm-track-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-track-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-track-copy strong { overflow: hidden; color: var(--ink-soft); font-size: 11px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.sm-track.active .sm-track-copy strong { color: var(--ink); font-weight: 600; }
.sm-track-copy small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-track-duration { flex: 0 0 auto; color: var(--muted); font-size: 8.5px; font-variant-numeric: tabular-nums; }
.sm-track-remove { display: grid; place-items: center; width: 24px; height: 24px; flex: 0 0 24px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--muted); opacity: .7; cursor: pointer; }
.sm-track-remove:hover { background: color-mix(in srgb, #c94a4a 9%, transparent); color: #c94a4a; opacity: 1; }
.sm-track-remove svg { width: 13px; height: 13px; }
.sm-mini-eq { position: absolute; right: 2px; bottom: 2px; display: flex; align-items: flex-end; gap: 1px; width: 12px; height: 12px; padding: 2px; border-radius: 50%; background: rgba(0, 0, 0, .55); }
.sm-mini-eq b { width: 1.5px; border-radius: 2px; background: #fff; animation: sm-bars .9s ease-in-out infinite alternate; }
.sm-mini-eq b:nth-child(1) { height: 3px; }
.sm-mini-eq b:nth-child(2) { height: 6px; animation-delay: .15s; }
.sm-mini-eq b:nth-child(3) { height: 4px; animation-delay: .28s; }

.sm-empty { display: grid; justify-items: center; gap: 5px; padding: 24px 14px; text-align: center; }
.sm-empty-orb { display: grid; place-items: center; width: 46px; height: 46px; margin-bottom: 3px; border-radius: 50%; background: var(--accent-soft); color: var(--accent-ink); font-size: 20px; }
.sm-empty strong { color: var(--ink); font-size: 13px; }
.sm-empty p { max-width: 220px; margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.sm-empty-action { margin-top: 6px; padding: 6px 13px; border: 1px solid var(--line); border-radius: 8px; background: var(--paper); color: var(--ink-soft); font-size: 10px; cursor: pointer; }
.sm-empty-action:hover { border-color: var(--accent); color: var(--accent-ink); }


.sm-search-hint { display: grid; gap: 5px; padding: 12px 4px 18px; }
.sm-search-hint-kicker { color: var(--accent-ink); font-size: 8px; font-weight: 800; letter-spacing: .16em; }
.sm-search-hint strong { color: var(--ink); font-size: 18px; letter-spacing: -.02em; }
.sm-search-hint p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.sm-suggestions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 7px; }
.sm-suggestions button { padding: 6px 10px; border: 1px solid var(--line); border-radius: 999px; background: var(--paper); color: var(--ink-soft); font-size: 10px; cursor: pointer; }
.sm-suggestions button:hover { border-color: var(--accent); color: var(--accent-ink); }
.sm-search-loading { display: grid; justify-items: center; gap: 8px; padding: 30px 0; color: var(--muted); font-size: 10px; }
.sm-search-loading .sm-spinner { border-color: color-mix(in srgb, var(--accent) 18%, var(--paper)); border-top-color: var(--accent); }
.sm-search-error { display: flex; align-items: center; gap: 9px; padding: 12px; border: 1px solid color-mix(in srgb, #c94a4a 24%, var(--line)); border-radius: 10px; background: color-mix(in srgb, #c94a4a 5%, var(--paper)); }
.sm-search-error > span { display: grid; place-items: center; width: 24px; height: 24px; flex: 0 0 24px; border-radius: 50%; background: color-mix(in srgb, #c94a4a 12%, var(--paper)); color: #c94a4a; font-size: 12px; font-weight: 800; }
.sm-search-error > div { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-search-error strong { color: var(--ink); font-size: 11px; }
.sm-search-error p { margin: 0; color: var(--muted); font-size: 9px; line-height: 1.5; }
.sm-result-list { display: flex; flex-direction: column; gap: 1px; padding-bottom: 2px; }
.sm-result { border-radius: 9px; }
.sm-result-main { display: flex; align-items: center; gap: 7px; width: 100%; padding: 5px 4px; border: 0; border-radius: 9px; background: transparent; color: var(--ink); text-align: left; cursor: pointer; }
.sm-result-main:hover { background: color-mix(in srgb, var(--accent) 6%, transparent); }
.sm-result-cover { display: grid; place-items: center; width: 34px; height: 34px; flex: 0 0 34px; overflow: hidden; border-radius: 8px; background: color-mix(in srgb, var(--accent) 12%, var(--paper)); color: var(--accent-ink); font-size: 13px; font-weight: 700; }
.sm-result-cover.image { background: var(--paper); }
.sm-result-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-result-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-result-copy strong { overflow: hidden; color: var(--ink-soft); font-size: 11.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.sm-result-copy small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-result-platform { color: color-mix(in srgb, var(--accent) 58%, var(--muted)); font-weight: 600; }
.sm-result-duration { flex: 0 0 auto; color: var(--muted); font-size: 8.5px; font-variant-numeric: tabular-nums; }
.sm-result-add { display: grid; place-items: center; width: 22px; height: 22px; flex: 0 0 22px; border: 1px solid var(--line); border-radius: 50%; color: var(--accent-ink); font-size: 12px; font-weight: 700; }
.sm-result-play { display: grid; place-items: center; width: 22px; height: 22px; flex: 0 0 22px; border: 1px solid var(--line); border-radius: 50%; color: var(--muted); transition: background .16s ease, color .16s ease, border-color .16s ease; }
.sm-result-play svg { width: 12px; height: 12px; }
.sm-result-main:hover .sm-result-play { border-color: var(--accent); background: var(--accent); color: #fff; }
.sm-result-play.playing { background: var(--accent); border-color: var(--accent); color: #fff; }
.sm-result-main:hover .sm-result-add { border-color: var(--accent); background: var(--accent); color: #fff; }
.sm-result-more { margin: 2px 0 0; color: var(--muted); font-size: 8.5px; text-align: center; }

.sm-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 13px 8px; border-top: 1px solid color-mix(in srgb, var(--line) 66%, transparent); color: var(--muted); font-size: 8px; }
.sm-foot span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.sm-fab-slot { display: flex; align-items: center; justify-content: flex-end; pointer-events: none; }

.sm-fab-shell {
  position: relative;
  display: flex;
  align-items: center;
  width: 54px;
  height: 54px;
  flex: 0 0 auto;
  padding: 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--accent) 24%, var(--line));
  border-radius: 999px;
  background: color-mix(in srgb, var(--paper) 88%, transparent);
  box-shadow: 0 12px 30px rgba(20, 45, 40, .18), inset 0 1px 0 rgba(255, 255, 255, .7);
  backdrop-filter: blur(18px);
  cursor: grab;
  pointer-events: auto;
  touch-action: none;
  user-select: none;
  will-change: width;
  transition: width .34s cubic-bezier(.22, .78, .25, 1), transform .2s ease, border-color .2s ease, box-shadow .2s ease;
}
.sm-fab-shell.hovered {
  width: 248px;
  transform: translateY(-3px);
  border-color: var(--accent);
  box-shadow: 0 16px 36px rgba(20, 45, 40, .22), 0 0 0 5px color-mix(in srgb, var(--accent) 10%, transparent);
}
.sm-fab-shell.dragging {
  cursor: grabbing;
  transition: none;
  transform: none;
  box-shadow: 0 12px 30px rgba(20, 45, 40, .18), inset 0 1px 0 rgba(255, 255, 255, .7);
}
.sm-fab {
  position: relative;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  flex: 0 0 54px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink);
  cursor: grab;
  pointer-events: auto;
  touch-action: none;
  user-select: none;
}
.sm-fab:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 999px; }
.sm-fab-shell:focus-within { outline: 2px solid var(--accent); outline-offset: 3px; }
.sm-fab-art { display: grid; will-change: transform; place-items: center; width: 40px; height: 40px; overflow: hidden; border-radius: 50%; background: linear-gradient(145deg, color-mix(in srgb, var(--accent) 72%, #fff), var(--accent)); color: #fff; font-size: 17px; font-weight: 800; box-shadow: inset 0 1px 0 rgba(255, 255, 255, .32), 0 5px 14px color-mix(in srgb, var(--accent) 20%, transparent); }
.sm-fab-art img { width: 100%; height: 100%; object-fit: cover; }
.sm-fab-eq { position: absolute; right: 4px; bottom: 4px; display: flex; align-items: flex-end; gap: 2px; width: 18px; height: 18px; padding: 3px; border-radius: 50%; background: var(--accent); box-shadow: 0 2px 8px color-mix(in srgb, var(--accent) 28%, transparent); }
.sm-fab-eq i { display: block; width: 2px; border-radius: 2px; background: #fff; animation: sm-bars 1s ease-in-out infinite alternate; }
.sm-fab-eq i:nth-child(1) { height: 5px; }
.sm-fab-eq i:nth-child(2) { height: 9px; animation-delay: .18s; }
.sm-fab-eq i:nth-child(3) { height: 7px; animation-delay: .34s; }
.sm-fab-eq i:nth-child(4) { height: 10px; animation-delay: .09s; }
.sm-fab-ring { position: absolute; inset: -4px; border: 1px solid color-mix(in srgb, var(--accent) 20%, transparent); border-radius: 50%; opacity: 0; animation: sm-pulse 2.2s ease-in-out infinite; }
.sm-app:not(.expanded) .sm-fab-ring { opacity: .32; }
.sm-fab-info {
  position: absolute;
  top: 50%;
  left: 58px;
  right: 58px;
  z-index: 1;
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
  opacity: 0;
  pointer-events: none;
  transform: translate(-7px, -50%);
  transition: opacity .12s ease, transform .24s cubic-bezier(.22, .78, .25, 1);
}
.sm-fab-info strong,
.sm-fab-info small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sm-fab-info strong { color: var(--ink); font-size: 12.5px; font-weight: 650; line-height: 1.25; }
.sm-fab-info small { color: var(--muted); font-size: 10.5px; line-height: 1.25; }
.sm-fab-shell.hovered .sm-fab-info {
  opacity: 1;
  transform: translate(0, -50%);
  transition-delay: .06s;
}
.sm-fab-shell.playing .sm-fab-art { animation: sm-disc-spin 7s linear infinite; }
.sm-fab-shell.playing .sm-fab-ring { opacity: .72; border-style: dashed; animation: sm-disc-orbit 3.4s linear infinite; }
.sm-fab-shell.playing { border-color: color-mix(in srgb, var(--accent) 62%, var(--line)); box-shadow: 0 14px 36px color-mix(in srgb, var(--accent) 22%, transparent), inset 0 1px 0 rgba(255, 255, 255, .7); }
.sm-fab-play {
  position: absolute;
  top: 50%;
  right: 9px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  opacity: 0;
  pointer-events: none;
  cursor: pointer;
  transform: translateY(-50%) scale(.78);
  box-shadow: 0 5px 14px color-mix(in srgb, var(--accent) 24%, transparent);
  transition: opacity .12s ease, transform .24s cubic-bezier(.22, .78, .25, 1), background .18s ease;
}
.sm-fab-shell.hovered .sm-fab-play {
  opacity: 1;
  pointer-events: auto;
  transform: translateY(-50%) scale(1);
  transition-delay: .08s;
}
.sm-fab-play:hover { background: var(--accent-ink); }
.sm-fab-shell.hovered .sm-fab-play:active { transform: translateY(-50%) scale(.94); }
.sm-fab-play:disabled { cursor: progress; }
.sm-fab-shell.hovered .sm-fab-play:disabled { opacity: .9; }
.sm-fab-play:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.sm-fab-play svg { width: 18px; height: 18px; }
.sm-fab-play .sm-spinner { width: 16px; height: 16px; border-width: 1.5px; }
.sm-fab-enter-active, .sm-fab-leave-active { transition: opacity .18s ease, transform .24s cubic-bezier(.2, .85, .2, 1.1); }
.sm-fab-enter-from, .sm-fab-leave-to { opacity: 0; transform: scale(.66) rotate(-12deg); }

.sm-panel-enter-active { transition: opacity .24s ease, transform .3s cubic-bezier(.18, .9, .24, 1.08); }
.sm-panel-leave-active { transition: opacity .17s ease, transform .2s ease; }
.sm-panel-enter-from, .sm-panel-leave-to { opacity: 0; transform: translate(var(--sm-panel-x, 0px), var(--sm-panel-y, 10px)) scale(.965); transform-origin: var(--sm-panel-origin, bottom right); }

.sm-nowview-body { display: flex; flex-direction: column; min-height: 0; overflow: hidden; }
.sm-nowview-head { display: grid; grid-template-columns: 48px minmax(0, 1fr) 48px; align-items: center; gap: 4px; flex: 0 0 auto; padding: 1px 0 7px; }
.sm-nowview-back { display: inline-flex; align-items: center; gap: 2px; padding: 4px 2px; border: 0; background: transparent; color: var(--muted); font-size: 9px; cursor: pointer; }
.sm-nowview-back:hover { color: var(--ink); }
.sm-nowview-back svg { width: 14px; height: 14px; }
.sm-nowview-spacer { display: block; }
.sm-nowview-tabs { display: flex; justify-content: center; gap: 3px; padding: 3px; border: 1px solid color-mix(in srgb, var(--line) 74%, transparent); border-radius: 999px; background: color-mix(in srgb, var(--accent) 5%, var(--paper)); }
.sm-nowview-tabs button { flex: 1; padding: 5px 8px; border: 0; border-radius: 999px; background: transparent; color: var(--muted); font-size: 10px; font-weight: 600; cursor: pointer; transition: background .18s ease, color .18s ease; }
.sm-nowview-tabs button.on { background: var(--accent); color: #fff; box-shadow: 0 3px 9px color-mix(in srgb, var(--accent) 24%, transparent); }
.sm-nowview-tabs em { margin-left: 1px; font-size: 8px; font-style: normal; opacity: .8; font-variant-numeric: tabular-nums; }
.sm-nowview-lyrics { display: flex; flex: 1; min-height: 0; flex-direction: column; }
.sm-nowview-hero { display: flex; align-items: center; gap: 10px; flex: 0 0 auto; padding: 4px 2px 8px; }
.sm-nowview-cover { position: relative; display: grid; place-items: center; width: 50px; height: 50px; flex: 0 0 50px; overflow: hidden; border-radius: 12px; background: linear-gradient(145deg, var(--accent), #8d6bff); color: #fff; font-size: 18px; font-weight: 800; box-shadow: 0 7px 16px color-mix(in srgb, var(--accent) 18%, transparent); }
.sm-nowview-cover.image { background: var(--paper); }
.sm-nowview-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-nowview-cover .sm-mini-eq { right: 4px; bottom: 4px; }
.sm-nowview-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-nowview-copy strong { overflow: hidden; color: var(--ink); font-size: 15px; letter-spacing: -.02em; text-overflow: ellipsis; white-space: nowrap; }
.sm-nowview-copy small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-lyric-source { width: fit-content; margin-top: 1px; padding: 2px 6px; border-radius: 999px; background: color-mix(in srgb, var(--accent) 7%, var(--paper)); color: var(--accent-ink); font-size: 8px; }
.sm-lyric-scroll { position: relative; flex: 1; min-height: 0; overflow-y: auto; padding: 2px 2px 6px; scrollbar-width: none; scroll-behavior: smooth; }
.sm-lyric-scroll::-webkit-scrollbar { display: none; }
.sm-lyric-list { display: flex; flex-direction: column; gap: 1px; padding: 34px 0 54px; }
.sm-lyric-line { width: 100%; padding: 7px 3px; border: 0; border-radius: 9px; background: transparent; color: var(--muted); font-size: 13px; line-height: 1.5; text-align: center; overflow-wrap: anywhere; opacity: .5; transform: scale(.97); transition: opacity .28s ease, color .28s ease, transform .28s cubic-bezier(.2, .8, .2, 1), background .2s ease; cursor: pointer; }
.sm-lyric-line:hover { background: color-mix(in srgb, var(--accent) 5%, transparent); opacity: .78; }
.sm-lyric-line.active {
  color: var(--accent-ink); font-size: 15px; font-weight: 700; opacity: 1; transform: scale(1.04);
  background: color-mix(in srgb, var(--accent) 7%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 13%, transparent);
  text-shadow: 0 2px 14px color-mix(in srgb, var(--accent) 16%, transparent);
  animation: sm-lyric-pop .3s ease-out;
}
.sm-lyric-line:disabled { cursor: default; }
.sm-lyric-list.is-plain .sm-lyric-line { opacity: .7; transform: none; cursor: default; }
.sm-lyric-list.is-plain .sm-lyric-line:hover { background: transparent; opacity: .7; }
.sm-lyric-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; min-height: 180px; padding: 24px 8px; color: var(--muted); text-align: center; }
.sm-lyric-state strong { color: var(--ink-soft); font-size: 12px; }
.sm-lyric-state p { margin: 0; font-size: 9px; line-height: 1.6; }
.sm-lyric-state .sm-list-spinner { width: 18px; height: 18px; flex-basis: 18px; border-width: 2px; color: var(--accent); margin-bottom: 2px; }
.sm-lyric-empty span { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 50%; background: color-mix(in srgb, var(--accent) 8%, var(--paper)); color: var(--accent-ink); font-size: 18px; }
.sm-nowview-queue { display: flex; flex: 1; min-height: 0; flex-direction: column; overflow-y: auto; padding-bottom: 2px; scrollbar-width: thin; }

@keyframes sm-bars { from { transform: scaleY(.55); opacity: .55; } to { transform: scaleY(1); opacity: 1; } }
@keyframes sm-spin { to { transform: rotate(360deg); } }
@keyframes sm-loader-ring {
  0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--accent) 24%, transparent); }
  65% { box-shadow: 0 0 0 5px color-mix(in srgb, var(--accent) 0%, transparent); }
  100% { box-shadow: 0 0 0 5px transparent; }
}
@keyframes sm-lyric-pop { from { opacity: .42; transform: scale(.96); } to { opacity: 1; transform: scale(1.04); } }
@keyframes sm-disc-spin { to { transform: rotate(360deg); } }
@keyframes sm-disc-orbit { to { transform: rotate(360deg); } }
@keyframes sm-pulse { 0%, 100% { transform: scale(1); opacity: .32; } 50% { transform: scale(1.16); opacity: .08; } }

@media (max-width: 920px) {
  .sm-app { right: 12px; bottom: 84px; }
  .sm-panel { position: fixed; left: 10px; right: 10px; bottom: 10px; width: auto; max-width: none; max-height: calc(100dvh - 20px); border-radius: 17px; }
}
@media (max-width: 520px) {
  .sm-panel { left: 8px; right: 8px; bottom: 8px; max-height: calc(100dvh - 16px); border-radius: 16px; }
  .sm-head { padding: 10px 12px 8px; }
  .sm-platform-btn { max-width: 104px; font-size: 9px; padding: 5px 7px; }
  .sm-platform-pop { width: 208px; max-height: 430px; }
  .sm-source-pill { max-width: 94px; }
  .sm-source-meta { margin: 6px 12px 0; }
  .sm-tabs { margin: 7px 12px 0; }
  .sm-nowview-head { grid-template-columns: 40px minmax(0, 1fr) 40px; }
  .sm-nowview-back span { display: none; }
  .sm-nowview-copy strong { font-size: 14px; }
  .sm-lyric-line { font-size: 12.5px; padding: 6px 2px; }
  .sm-lyric-line.active { font-size: 14px; }
  .sm-body { padding: 9px 12px 3px; }
  .sm-nowbar { padding: 6px 12px calc(7px + env(safe-area-inset-bottom)); }
  .sm-foot { padding-bottom: calc(7px + env(safe-area-inset-bottom)); }
}
@media (hover: none), (pointer: coarse), (max-width: 920px) {
  .sm-fab-shell, .sm-fab-shell.hovered {
    width: 54px;
    padding: 0;
    transform: none;
    transition: border-color .18s ease, box-shadow .18s ease;
  }
  .sm-fab-info, .sm-fab-play { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .sm-fab-ring, .sm-now-playing b, .sm-mini-eq b, .sm-fab-eq i, .sm-fab-shell.playing .sm-fab-art, .sm-fab-shell.playing .sm-fab-ring { animation: none; }
  .sm-fab-shell, .sm-fab-info, .sm-fab-play { transition: none; }
  .sm-panel-enter-active, .sm-panel-leave-active, .sm-fab-enter-active, .sm-fab-leave-active { transition: opacity .12s linear; }
  .sm-lyric-line, .sm-lyric-line.active, .sm-nowview-tabs button { transition: none; animation: none; transform: none; }
}
</style>
