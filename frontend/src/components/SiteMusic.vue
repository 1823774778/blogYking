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
const CHART_TTL_MILLIS = 10 * 60 * 1000
const FALLBACK_PLATFORM_LABELS = {
  wy: '网易音乐',
  tx: 'QQ 音乐',
  kw: '酷我音乐',
  kg: '酷狗音乐',
  mg: '咪咕音乐'
}
// 前台展示名沿用用户熟悉的一套称呼，底层仍用平台代码请求。
const PLATFORM_DISPLAY_NAMES = {
  kg: '小枸音乐',
  kw: '小蜗音乐',
  tx: '小秋音乐',
  wy: '小芸音乐',
  mg: '小蜜音乐'
}
const PLATFORM_MENU_ORDER = ['kg', 'kw', 'tx', 'wy', 'mg']

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
const searchKeyword = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const searchError = ref('')
const searchFocused = ref(false)
const view = ref('charts')
const activePlatform = ref('wy')
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
let fabHoverTimer = null
let fabFocusTimer = null
let platformInitialized = false
let sourceDetailsSeq = 0
const playlistDetailCache = new Map()

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
const menuPlatforms = computed(() => {
  const byCode = new Map(sourcePlatforms.value.map((platform) => [platform.code, platform]))
  return PLATFORM_MENU_ORDER.map((code) => byCode.get(code)).filter(Boolean)
})
// 搜索当前由网易云曲库承接；音源没有声明网易云时，搜索结果无法播放，所以整个搜索入口隐藏。
const searchPlatforms = computed(() => sourcePlatforms.value.filter((platform) => platform.search))
const searchPlatformLabel = computed(() => searchPlatforms.value[0]?.label || '网易云')
const hasSearch = computed(() => sourceActive.value && searchPlatforms.value.length > 0)
const playlistCards = computed(() => playlistItems.value)
const hasPlaylists = computed(() => hasCharts.value)
const sourceFeatureLabel = computed(() => {
  const features = []
  if (hasPlaylists.value) features.push('歌单')
  if (hasRanking.value) features.push('排行榜')
  if (hasSearch.value) features.push('搜索')
  features.push('队列', '音量')
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
  if (!raw) return `${activePlatformLabel.value}热歌榜`
  const realLabel = sourceLabelFor(activePlatform.value)
  return realLabel && raw.includes(realLabel) ? raw.replace(realLabel, activePlatformLabel.value) : raw
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
const qualityText = (quality) => {
  const value = String(quality || '').trim()
  const labels = { '128k': '128K', '320k': '320K', 'flac': 'FLAC', 'flac24': 'FLAC 24' }
  return labels[value.toLowerCase()] || value.toUpperCase()
}
const sourceQualityText = computed(() => sourceQualities.value.map(qualityText).join(' · '))
const resolveQuality = computed(() => sourceQualities.value.includes('320k') ? '320k' : (sourceQualities.value[0] || '128k'))
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
const quietEmpty = computed(() => queue.value.length === 0 && !currentTrack.value)

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
        url: track.source === 'local' ? track.url : ''
      }))
    }))
  } catch (e) {
    /* 隐私模式下 localStorage 可能不可用 */
  }
}

watch(queue, persistState, { deep: true })
watch([mode, volume, muted], persistState)
watch(volume, (value) => {
  if (audio.value) audio.value.volume = Math.min(1, Math.max(0, value))
})
watch(muted, (value) => {
  if (audio.value) audio.value.muted = value
})

function reconcileAvailableView() {
  if ((playlistPlatforms.value.length || rankingPlatforms.value.length) && !platformInitialized) {
    // 默认选中更稳定的解析通道；真实歌单不预加载歌曲。
    activePlatform.value = preferredPlatform()
    platformInitialized = true
  } else if (view.value === 'ranking' && !rankingPlatforms.value.some((platform) => platform.code === activePlatform.value)) {
    activePlatform.value = preferredPlatform()
  } else if (view.value !== 'ranking' && hasCharts.value && !playlistPlatforms.value.some((platform) => platform.code === activePlatform.value)) {
    activePlatform.value = preferredPlatform()
  }
  if (view.value === 'playlist' && !activePlaylistId.value) {
    view.value = 'charts'
  }
  if (!panelOpen.value) return
  const allowed = [
    ...(localActive.value ? ['local'] : []),
    ...(hasCharts.value ? ['charts', 'playlist'] : []),
    ...(hasRanking.value ? ['ranking'] : []),
    'queue',
    ...(hasSearch.value ? ['search'] : [])
  ]
  if (allowed.includes(view.value)) {
    if (view.value === 'charts') loadPlaylists()
    else if (view.value === 'playlist') loadPlaylistDetail()
    else if (view.value === 'ranking') loadRanking()
    return
  }
  view.value = defaultView()
  if (view.value === 'local') loadLocalTracks(false)
  else if (view.value === 'charts') loadPlaylists()
  else if (view.value === 'ranking') loadRanking()
}

watch([contentMode, hasSource, hasCharts, hasRanking, hasSearch, localActive], reconcileAvailableView)
watch(sourcePlatformCodes, reconcileAvailableView)
watch(sourceDetails, reconcileAvailableView, { deep: true })
watch(sourceUrl, () => {
  platformInitialized = false
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
    return 'queue'
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
    if (seq === sourceDetailsSeq) sourceDetailsLoading.value = false
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
  const panelWidth = Math.min(392, viewportWidth - margin * 2)
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
  const maxHeight = Math.min(690, Math.max(240, availableHeight))
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
  computePanelPlacement()
  const target = defaultView()
  panelOpen.value = true
  view.value = target
  if (target === 'charts') {
    resetChartScroll()
    loadPlaylists()
  } else if (target === 'ranking') {
    resetChartScroll()
    loadRanking()
  } else if (target === 'search') {
    nextTick(() => searchInput.value?.focus())
  } else if (target === 'local') {
    loadLocalTracks()
  }
}

function closePanel() {
  panelOpen.value = false
  platformMenuOpen.value = false
  if (fabFocusTimer) clearTimeout(fabFocusTimer)
  fabFocusTimer = setTimeout(() => fab.value?.focus({ preventScroll: true }), 190)
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
      view.value = 'queue'
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

function selectPlatformView(code, target) {
  const platform = String(code || '').trim()
  if (!platform) return
  platformMenuOpen.value = false
  platformInitialized = true
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
        view.value = 'queue'
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
  const index = addTrackToQueue(track)
  playIndex(index, true)
}

function playAllLocal() {
  if (!localTracks.value.length) return
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
  view.value = 'queue'
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
      timeout: 30000
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
    urlLoading: false
  })
  return queue.value.length - 1
}

function selectChartTrack(track) {
  const index = addTrackToQueue(track)
  playIndex(index, true)
}

function playCollection(tracks) {
  if (!tracks.length) return
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

function clearFabHoverTimer() {
  if (fabHoverTimer) {
    clearTimeout(fabHoverTimer)
    fabHoverTimer = null
  }
}

function onFabPointerEnter() {
  if (panelOpen.value || fabDrag) return
  const hoverCapable = window.matchMedia?.('(hover: hover) and (pointer: fine)').matches
  if (!hoverCapable) return
  clearFabHoverTimer()
  fabHoverTimer = setTimeout(() => {
    fabHoverTimer = null
    if (!panelOpen.value && !fabDrag && !fabMoved.value) openPanel()
  }, 420)
}

function onFabPointerLeave() {
  clearFabHoverTimer()
}

function beginFabDrag(event) {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  clearFabHoverTimer()
  const element = event.currentTarget
  const rect = element.getBoundingClientRect()
  fabDrag = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    offsetX: fabOffset.value.x,
    offsetY: fabOffset.value.y,
    rectLeft: rect.left - fabOffset.value.x,
    rectTop: rect.top - fabOffset.value.y,
    width: rect.width,
    height: rect.height
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
          quality
        },
        timeout: 30000
      })
      if (seq !== playSeq) return null
      const url = res.data?.proxyUrl || res.data?.url
      if (url) return { url, quality: res.data?.quality || quality }
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
  return PLATFORM_DISPLAY_NAMES[code] || sourceLabelFor(code)
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
        const resolved = await requestPlaybackUrl(candidate, seq)
        if (!resolved || seq !== playSeq || queue.value[currentIndex.value] !== track) return false
        applyFallbackCandidate(track, candidate)
        track.url = resolved.url
        track.quality = resolved.quality
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
    const resolved = await requestPlaybackUrl(track, seq)
    if (!resolved || seq !== playSeq) return
    if (queue.value[currentIndex.value] !== track) return
    track.url = resolved.url
    track.quality = resolved.quality
    track.urlRetried = retrying
    track.urlLoading = false
    loadingTrack.value = false
    await nextTick()
    if (seq !== playSeq) return
    startAudio(track, autoplay)
  } catch (e) {
    if (seq !== playSeq) return
    const switched = await tryFallbackPlayback(track, autoplay, seq)
    if (seq !== playSeq || switched) return
    track.urlLoading = false
    loadingTrack.value = false
    track.urlError = track.fallbackMessage || apiErrorMessage(e, '该歌曲暂时无法播放')
    playing.value = false
    toast(track.urlError, 'error')
  }
}

function startAudio(track, autoplay = true) {
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

function togglePlayback() {
  if (loadingTrack.value) return
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
  const index = MODES.findIndex((item) => item.id === mode.value)
  mode.value = MODES[(index + 1) % MODES.length].id
  toast(`播放模式：${modeMeta.value.label}`, 'info')
}

function seek(event) {
  const element = audio.value
  if (!element || !currentDuration.value) return
  const percent = Number(event.target.value)
  element.currentTime = (percent / 100) * currentDuration.value
  progress.value = element.currentTime
}

function setVolume(event) {
  const next = Number(event.target.value) / 100
  volume.value = Number.isFinite(next) ? Math.min(1, Math.max(0, next)) : 0.8
  if (next > 0 && muted.value) muted.value = false
}

function toggleMute() {
  muted.value = !muted.value
}

function removeTrack(index) {
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

function onAudioError() {
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
  if (platformMenuOpen.value) {
    platformMenuOpen.value = false
    return
  }
  if (panelOpen.value) closePanel()
}

function onDocumentClick(event) {
  if (!platformMenuOpen.value) return
  if (platformMenu.value?.contains(event.target)) return
  platformMenuOpen.value = false
}

function onVisibilityChange() {
  if (document.hidden) {
    playing.value = audio.value ? !audio.value.paused : false
  }
}

onMounted(() => {
  loadPersistedState()
  loadLayoutState()
  compactViewport.value = window.innerWidth <= 920
  activePlatform.value = preferredPlatform()
  view.value = defaultView()
  loadLocalTracks(true)
  loadSourceCapabilities()
  if (audio.value) {
    audio.value.volume = volume.value
    audio.value.muted = muted.value
  }
  document.addEventListener('keydown', onKeydown)
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('resize', onViewportResize)
  window.addEventListener('deek:music-local-changed', onLocalMusicChanged)
})

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
  if (fabMoveTimer) clearTimeout(fabMoveTimer)
  clearFabHoverTimer()
  if (fabFocusTimer) clearTimeout(fabFocusTimer)
  searchSeq += 1
  playSeq += 1
  playlistSeq.value += 1
  playlistDetailSeq.value += 1
  endFabDrag()
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
      @play="playing = true"
      @pause="playing = false"
      @timeupdate="progress = audio?.currentTime || 0"
      @loadedmetadata="duration = audio?.duration || currentTrack?.duration || 0"
      @durationchange="duration = audio?.duration || currentTrack?.duration || 0"
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
              <span class="sm-platform-group">推荐歌单</span>
              <button
                v-for="platform in menuPlatforms"
                :key="'playlist-' + platform.code"
                type="button"
                class="sm-platform-option"
                :class="{ on: activePlatform === platform.code && view !== 'ranking' }"
                :disabled="!platform.playlists"
                @click="selectPlatformView(platform.code, 'charts')"
              >
                <i class="sm-platform-dot" :style="{ background: platform.color }" aria-hidden="true"></i>
                <span>{{ platformDisplayName(platform.code) }}</span>
                <small>歌单</small>
              </button>
              <span class="sm-platform-group ranking">排行榜</span>
              <button
                v-for="platform in menuPlatforms"
                :key="'ranking-' + platform.code"
                type="button"
                class="sm-platform-option"
                :class="{ on: activePlatform === platform.code && view === 'ranking' }"
                :disabled="!platform.charts"
                @click="selectPlatformView(platform.code, 'ranking')"
              >
                <i class="sm-platform-dot" :style="{ background: platform.color }" aria-hidden="true"></i>
                <span>{{ platformDisplayName(platform.code) }}</span>
                <small>排行榜</small>
              </button>
            </div>
          </div>
          <button type="button" class="sm-icon-btn sm-close" aria-label="收起播放器" @click="closePanel">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m6 9 6 6 6-6" /></svg>
          </button>
        </header>

        <div v-if="sourceActive && (sourceQualityText || sourceDetailsLoading || sourceMeta.initAlert)" class="sm-source-meta">
          <span v-if="sourceQualityText" class="sm-source-chip">音质 {{ sourceQualityText }}</span>
          <span v-if="sourceDetailsLoading" class="sm-source-chip muted">读取音源能力…</span>
          <span v-if="sourceMeta.initAlert" class="sm-source-chip warn">音源提示</span>
        </div>
        <p v-if="sourceActive && sourceMeta.initAlert" class="sm-source-alert">{{ sourceMeta.initAlert }}</p>

        <nav class="sm-tabs" aria-label="播放器视图">
          <button v-if="localActive" type="button" :class="{ on: view === 'local' }" @click="showLocalView">
            本地音乐 <em v-if="localCount">{{ localCount }}</em>
          </button>
          <button v-if="hasCharts" type="button" :class="{ on: view === 'charts' || view === 'playlist' }" @click="showChartsView">歌单</button>
          <button v-if="hasRanking" type="button" :class="{ on: view === 'ranking' }" @click="showRankingView">排行榜</button>
          <button type="button" :class="{ on: view === 'queue' }" @click="view = 'queue'">
            播放列表 <em>{{ queue.length }}</em>
          </button>
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
              @click="selectLocalTrack(track)"
            >
              <span class="sm-local-cover">{{ coverLetter(track) }}</span>
              <span class="sm-local-copy">
                <strong>{{ track.title }}</strong>
                <small>{{ track.artist || '未知歌手' }}<template v-if="track.album"> · {{ track.album }}</template></small>
              </span>
              <span class="sm-local-duration">{{ track.duration ? formatTime(track.duration) : '本地' }}</span>
              <span class="sm-local-play" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 6 9 6-9 6V6Z" /></svg>
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
              @click="selectChartTrack(track)"
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
              <span class="sm-chart-play" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 6 9 6-9 6V6Z" /></svg>
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
              @click="selectChartTrack(track)"
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
              <span class="sm-chart-play" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 6 9 6-9 6V6Z" /></svg>
              </span>
            </button>
          </div>

          <div v-else class="sm-empty sm-chart-empty">
            <span class="sm-empty-orb">♪</span>
            <strong>这个平台暂时没有排行榜</strong>
            <p>换个平台，或刷新后再试。</p>
          </div>
        </div>

        <div v-else-if="view === 'queue'" class="sm-body sm-queue-body">
          <div v-if="currentTrack" class="sm-now">
            <div class="sm-now-cover">
              <img v-if="currentTrack.cover" :src="currentTrack.cover" alt="" referrerpolicy="no-referrer" @error="$event.target.style.display = 'none'" />
              <span v-if="!currentTrack.cover">{{ coverLetter(currentTrack) }}</span>
              <i v-if="playing" class="sm-now-playing" aria-hidden="true"><b></b><b></b><b></b></i>
            </div>
            <div class="sm-now-copy">
              <strong>{{ currentTrack.title }}</strong>
              <span>{{ currentTrack.artist }}<template v-if="currentTrack.album"> · {{ currentTrack.album }}</template></span>
              <small v-if="currentTrack.urlLoading">正在获取播放地址…</small>
              <small v-else-if="currentTrack.urlError" class="danger">{{ currentTrack.urlError }}</small>
            </div>
            <span v-if="currentTrack.fee > 0" class="sm-vip">VIP</span>
          </div>

          <div v-if="currentTrack" class="sm-progress">
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
            <div class="sm-time-row">
              <span>{{ formatTime(progress) }}</span>
              <span>{{ formatTime(currentDuration) }}</span>
            </div>
          </div>

          <div class="sm-controls">
            <button type="button" class="sm-mode" :aria-label="'播放模式：' + modeMeta.label" :title="modeMeta.hint" @click="cycleMode">
              <svg v-if="mode === 'shuffle'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 6h3l4.5 6L9 18H6" /><path d="m18 3 3 3-3 3M3 18h3l2-2.7M14 9l4 9h3M18 21l3-3" /></svg>
              <svg v-else-if="mode === 'single'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M17 2l3 3-3 3M20 5H8a5 5 0 0 0-5 5v1M7 22l-3-3 3-3M4 19h12a5 5 0 0 0 5-5v-1" /><path d="M12 10v4M10.7 11.1 12 10l1.3 1.1" /></svg>
              <svg v-else-if="mode === 'sequence'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 6h14M14 2l4 4-4 4M20 18H6M10 14l-4 4 4 4" /></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M17 2l3 3-3 3M20 5H8a5 5 0 0 0-5 5v1M7 22l-3-3 3-3M4 19h12a5 5 0 0 0 5-5v-1" /></svg>
              <span>{{ modeMeta.label }}</span>
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
            <button type="button" class="sm-volume-toggle" :aria-label="muted ? '取消静音' : '静音'" @click="toggleMute">
              <svg v-if="muted || volume === 0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 9h4l5-4v14l-5-4H4V9Z" /><path d="m17 9 4 4M21 9l-4 4" /></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 9h4l5-4v14l-5-4H4V9Z" /><path d="M16 8a5 5 0 0 1 0 8M18.5 5.5a9 9 0 0 1 0 13" /></svg>
            </button>
          </div>

          <div class="sm-volume">
            <span>{{ muted ? '已静音' : '音量' }}</span>
            <input
              type="range"
              min="0"
              max="100"
              step="1"
              :value="volumePercent"
              aria-label="音量"
              :style="{ '--sm-volume': volumePercent + '%' }"
              @input="setVolume"
            />
            <strong>{{ volumePercent }}</strong>
          </div>

          <div class="sm-list-head">
            <div>
              <strong>播放队列</strong>
              <span>{{ queue.length }} 首</span>
            </div>
            <button v-if="queue.length" type="button" class="sm-text-btn" @click="clearQueue">清空</button>
          </div>

          <div class="sm-track-list" role="list" aria-label="播放队列">
            <div
              v-for="(track, index) in queue"
              :key="track.id + '-' + index"
              class="sm-track"
              :class="{ active: index === currentIndex }"
              role="listitem"
            >
              <button type="button" class="sm-track-main" @click="playIndex(index, true)">
                <span class="sm-track-cover" :class="{ image: track.cover }">
                  <img v-if="track.cover" :src="track.cover" alt="" referrerpolicy="no-referrer" @error="$event.target.style.display = 'none'" />
                  <template v-else>{{ coverLetter(track) }}</template>
                  <i v-if="index === currentIndex && playing" class="sm-mini-eq" aria-hidden="true"><b></b><b></b><b></b></i>
                </span>
                <span class="sm-track-copy">
                  <strong>{{ track.title }}</strong>
                  <small>{{ track.artist }}<template v-if="track.urlError"> · 播放失败</template></small>
                </span>
                <span class="sm-track-duration">{{ track.duration ? formatTime(track.duration) : '—' }}</span>
              </button>
              <button type="button" class="sm-track-remove" aria-label="从队列移除" @click="removeTrack(index)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="m7 7 10 10M17 7 7 17" /></svg>
              </button>
            </div>
          </div>

          <div v-if="quietEmpty" class="sm-empty">
            <span class="sm-empty-orb">♪</span>
            <strong>还没有播放队列</strong>
            <p>{{ hasSource ? '搜索一首歌，或者从搜索结果里挑一首开始。' : '从本地音乐里挑一首开始播放。' }}</p>
            <button type="button" class="sm-empty-action" @click="openSearchOrLocal">{{ hasSource ? '去搜索' : '浏览本地音乐' }}</button>
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
            <p>搜索{{ searchPlatformLabel }}曲库，播放地址由 {{ sourceName }} 解析。</p>
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
              <button type="button" class="sm-result-main" @click="selectSearchResult(track)">
                <span class="sm-result-cover" :class="{ image: track.cover }">
                  <img v-if="track.cover" :src="track.cover" alt="" referrerpolicy="no-referrer" @error="$event.target.style.display = 'none'" loading="lazy" />
                  <template v-else>{{ coverLetter(track) }}</template>
                </span>
                <span class="sm-result-copy">
                  <strong>{{ track.title }}</strong>
                  <small>{{ track.artist }}<template v-if="track.album"> · {{ track.album }}</template></small>
                </span>
                <span v-if="track.fee > 0" class="sm-vip">VIP</span>
                <span v-else class="sm-result-duration">{{ track.duration ? formatTime(track.duration) : '' }}</span>
                <span class="sm-result-add" aria-hidden="true">＋</span>
              </button>
            </div>
            <p class="sm-result-more">搜索由{{ searchPlatformLabel }}曲库提供 · 点击歌曲加入播放队列</p>
          </div>
        </div>

        <footer class="sm-foot">
          <span>{{ footerSourceLabel }}</span>
          <span v-if="currentTrack">{{ playing ? '正在播放' : '已暂停' }} · {{ currentTrack.title }}</span>
          <span v-else>{{ sourceActive ? sourceFeatureLabel : '本地音乐 / 队列 / 音量' }}</span>
        </footer>
      </aside>
    </transition>

    <div class="sm-fab-slot" :style="fabStyle">
      <transition name="sm-fab">
        <button
          v-if="!panelOpen"
          ref="fab"
          type="button"
          class="sm-fab"
          :class="{ dragging: fabDragging, playing }"
          aria-label="打开站点音乐"
          title="打开站点音乐；桌面端鼠标悬停展开，按住可拖动"
          @pointerenter="onFabPointerEnter"
          @pointerleave="onFabPointerLeave"
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
  bottom: 90px;
  display: flex;
  flex-direction: column;
  width: 392px;
  max-width: calc(100vw - 32px);
  max-height: min(82dvh, 690px);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--accent) 20%, var(--line));
  border-radius: 22px;
  background: color-mix(in srgb, var(--paper) 94%, transparent);
  box-shadow: 0 28px 80px rgba(14, 35, 30, .26), inset 0 1px 0 rgba(255, 255, 255, .7);
  backdrop-filter: blur(26px);
  pointer-events: auto;
}

.sm-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 15px 12px;
  border-bottom: 1px solid color-mix(in srgb, var(--line) 66%, transparent);
  user-select: none;
}

.sm-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  flex: 1;
}
.sm-brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 10px;
  background: linear-gradient(145deg, var(--accent), color-mix(in srgb, var(--accent) 58%, #8d6bff));
  color: #fff;
  font-size: 16px;
  font-weight: 800;
  box-shadow: 0 5px 14px color-mix(in srgb, var(--accent) 24%, transparent);
}
.sm-brand-mark svg { width: 15px; height: 15px; opacity: .92; }
.sm-brand > div { display: grid; gap: 2px; min-width: 0; }
.sm-brand strong { color: var(--ink); font-size: 14px; letter-spacing: .01em; }
.sm-brand small { overflow: hidden; color: var(--muted); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-menu { position: relative; flex: 0 1 auto; min-width: 0; }
.sm-platform-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 132px;
  min-width: 0;
  padding: 6px 9px;
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
  margin: 10px 15px 0;
  padding: 3px;
  border: 1px solid color-mix(in srgb, var(--line) 70%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--accent) 4%, var(--paper));
}
.sm-tabs button {
  flex: 1;
  padding: 7px 9px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}
.sm-tabs button.on { background: var(--paper); color: var(--ink); box-shadow: 0 3px 10px rgba(17, 24, 39, .06); }
.sm-tabs em { color: var(--accent-ink); font-size: 9px; font-style: normal; }

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
  gap: 8px;
  width: 100%;
  padding: 6px 4px;
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
.sm-chart-cover { display: grid; place-items: center; width: 38px; height: 38px; flex: 0 0 38px; overflow: hidden; border-radius: 9px; background: color-mix(in srgb, var(--accent) 12%, var(--paper)); color: var(--accent-ink); font-size: 13px; font-weight: 700; }
.sm-chart-cover.image { background: var(--paper); }
.sm-chart-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-chart-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-chart-copy strong { overflow: hidden; color: var(--ink-soft); font-size: 11.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.sm-chart-copy small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-chart-duration { flex: 0 0 auto; color: var(--muted); font-size: 8.5px; font-variant-numeric: tabular-nums; }
.sm-chart-play { display: grid; place-items: center; width: 24px; height: 24px; flex: 0 0 24px; border: 1px solid var(--line); border-radius: 50%; color: var(--muted); transition: background .16s ease, color .16s ease, border-color .16s ease; }
.sm-chart-play svg { width: 13px; height: 13px; }
.sm-chart-empty { padding-top: 20px; }

.sm-local-body { display: flex; flex-direction: column; gap: 10px; }
.sm-local-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 10px; flex: 0 0 auto; padding: 2px 2px 0; }
.sm-local-head > div:first-child { display: grid; gap: 2px; min-width: 0; }
.sm-local-head strong { overflow: hidden; color: var(--ink); font-size: 16px; letter-spacing: -.02em; text-overflow: ellipsis; white-space: nowrap; }
.sm-local-head small { overflow: hidden; color: var(--muted); font-size: 8.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-local-list { display: flex; flex-direction: column; gap: 2px; padding-bottom: 3px; }
.sm-local-row { display: flex; align-items: center; gap: 9px; width: 100%; padding: 7px 5px; border: 0; border-radius: 10px; background: transparent; color: var(--ink); text-align: left; cursor: pointer; }
.sm-local-row:hover { background: color-mix(in srgb, var(--accent) 6%, transparent); }
.sm-local-row:hover .sm-local-play { border-color: var(--accent); background: var(--accent); color: #fff; }
.sm-local-cover { display: grid; place-items: center; width: 38px; height: 38px; flex: 0 0 38px; border-radius: 9px; background: linear-gradient(145deg, color-mix(in srgb, var(--accent) 18%, var(--paper)), color-mix(in srgb, #8d6bff 12%, var(--paper))); color: var(--accent-ink); font-size: 13px; font-weight: 700; }
.sm-local-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-local-copy strong { overflow: hidden; color: var(--ink-soft); font-size: 11.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.sm-local-copy small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-local-duration { flex: 0 0 auto; color: var(--muted); font-size: 8.5px; font-variant-numeric: tabular-nums; }
.sm-local-play { display: grid; place-items: center; width: 24px; height: 24px; flex: 0 0 24px; border: 1px solid var(--line); border-radius: 50%; color: var(--muted); transition: background .16s ease, color .16s ease, border-color .16s ease; }
.sm-local-play svg { width: 13px; height: 13px; }
.sm-local-empty { padding-top: 18px; }

.sm-body { min-height: 0; flex: 1; overflow-y: auto; padding: 12px 15px 4px; scrollbar-width: thin; }
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

.sm-list-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 2px; padding: 4px 1px 0; border-top: 1px solid color-mix(in srgb, var(--line) 60%, transparent); }
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
.sm-result-main { display: flex; align-items: center; gap: 8px; width: 100%; padding: 6px 4px; border: 0; border-radius: 9px; background: transparent; color: var(--ink); text-align: left; cursor: pointer; }
.sm-result-main:hover { background: color-mix(in srgb, var(--accent) 6%, transparent); }
.sm-result-cover { display: grid; place-items: center; width: 36px; height: 36px; flex: 0 0 36px; overflow: hidden; border-radius: 8px; background: color-mix(in srgb, var(--accent) 12%, var(--paper)); color: var(--accent-ink); font-size: 13px; font-weight: 700; }
.sm-result-cover.image { background: var(--paper); }
.sm-result-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-result-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.sm-result-copy strong { overflow: hidden; color: var(--ink-soft); font-size: 11.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.sm-result-copy small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.sm-result-duration { flex: 0 0 auto; color: var(--muted); font-size: 8.5px; font-variant-numeric: tabular-nums; }
.sm-result-add { display: grid; place-items: center; width: 22px; height: 22px; flex: 0 0 22px; border: 1px solid var(--line); border-radius: 50%; color: var(--accent-ink); font-size: 12px; font-weight: 700; }
.sm-result-main:hover .sm-result-add { border-color: var(--accent); background: var(--accent); color: #fff; }
.sm-result-more { margin: 2px 0 0; color: var(--muted); font-size: 8.5px; text-align: center; }

.sm-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 9px 15px 11px; border-top: 1px solid color-mix(in srgb, var(--line) 66%, transparent); color: var(--muted); font-size: 8.5px; }
.sm-foot span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.sm-fab-slot { display: flex; align-items: center; justify-content: flex-end; pointer-events: none; }
.sm-fab-slot .sm-fab { pointer-events: auto; }

.sm-fab { position: relative; display: grid; place-items: center; width: 54px; height: 54px; flex: 0 0 54px; padding: 0; border: 1px solid color-mix(in srgb, var(--accent) 24%, var(--line)); border-radius: 50%; background: color-mix(in srgb, var(--paper) 88%, transparent); box-shadow: 0 12px 30px rgba(20, 45, 40, .18), inset 0 1px 0 rgba(255, 255, 255, .7); backdrop-filter: blur(18px); cursor: grab; pointer-events: auto; touch-action: none; user-select: none; }
.sm-fab.dragging { cursor: grabbing; transition: none; }
.sm-fab:hover { transform: translateY(-3px) scale(1.03); border-color: var(--accent); box-shadow: 0 16px 36px rgba(20, 45, 40, .22), 0 0 0 5px color-mix(in srgb, var(--accent) 10%, transparent); }
.sm-fab.dragging:hover { transform: none; box-shadow: 0 12px 30px rgba(20, 45, 40, .18), inset 0 1px 0 rgba(255, 255, 255, .7); }
.sm-fab:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
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
.sm-fab.playing .sm-fab-art { animation: sm-disc-spin 7s linear infinite; }
.sm-fab.playing .sm-fab-ring { opacity: .72; border-style: dashed; animation: sm-disc-orbit 3.4s linear infinite; }
.sm-fab.playing { border-color: color-mix(in srgb, var(--accent) 62%, var(--line)); box-shadow: 0 14px 36px color-mix(in srgb, var(--accent) 22%, transparent), inset 0 1px 0 rgba(255, 255, 255, .7); }
.sm-fab-enter-active, .sm-fab-leave-active { transition: opacity .18s ease, transform .24s cubic-bezier(.2, .85, .2, 1.1); }
.sm-fab-enter-from, .sm-fab-leave-to { opacity: 0; transform: scale(.66) rotate(-12deg); }

.sm-panel-enter-active { transition: opacity .24s ease, transform .3s cubic-bezier(.18, .9, .24, 1.08); }
.sm-panel-leave-active { transition: opacity .17s ease, transform .2s ease; }
.sm-panel-enter-from, .sm-panel-leave-to { opacity: 0; transform: translate(var(--sm-panel-x, 0px), var(--sm-panel-y, 10px)) scale(.965); transform-origin: var(--sm-panel-origin, bottom right); }

@keyframes sm-bars { from { transform: scaleY(.55); opacity: .55; } to { transform: scaleY(1); opacity: 1; } }
@keyframes sm-spin { to { transform: rotate(360deg); } }
@keyframes sm-disc-spin { to { transform: rotate(360deg); } }
@keyframes sm-disc-orbit { to { transform: rotate(360deg); } }
@keyframes sm-pulse { 0%, 100% { transform: scale(1); opacity: .32; } 50% { transform: scale(1.16); opacity: .08; } }

@media (max-width: 920px) {
  .sm-app { right: 12px; bottom: 84px; }
  .sm-panel { position: fixed; left: 12px; right: 12px; bottom: 12px; width: auto; max-width: none; max-height: calc(100dvh - 24px); border-radius: 20px; }
}
@media (max-width: 520px) {
  .sm-panel { left: 0; right: 0; bottom: 0; max-height: calc(100dvh - 10px); border-radius: 18px 18px 0 0; }
  .sm-head { padding: 12px 13px 10px; }
  .sm-platform-btn { max-width: 108px; font-size: 9.5px; padding: 5px 8px; }
  .sm-platform-pop { width: 214px; max-height: 430px; }
.sm-source-pill { max-width: 94px; }
  .sm-source-meta { margin: 7px 13px 0; }
  .sm-foot { padding-bottom: calc(11px + env(safe-area-inset-bottom)); }
}
@media (prefers-reduced-motion: reduce) {
  .sm-fab-ring, .sm-now-playing b, .sm-mini-eq b, .sm-fab-eq i, .sm-fab.playing .sm-fab-art, .sm-fab.playing .sm-fab-ring { animation: none; }
  .sm-panel-enter-active, .sm-panel-leave-active, .sm-fab-enter-active, .sm-fab-leave-active { transition: opacity .12s linear; }
}
</style>
