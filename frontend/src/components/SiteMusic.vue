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
const view = ref('queue')
const searchInput = ref(null)
const fab = ref(null)
let searchTimer = null
let searchSeq = 0
let playSeq = 0

const sourceUrl = computed(() => String(state.site.musicSourceUrl || '').trim())
const sourceName = computed(() => String(state.site.musicSourceName || 'LX 音源').trim())
const sourceVersion = computed(() => String(state.site.musicSourceVersion || '').trim())
const sourcePlatforms = computed(() => {
  const labels = { wy: '网易云', tx: 'QQ', kw: '酷我', kg: '酷狗', mg: '咪咕' }
  return String(state.site.musicSourcePlatforms || '')
    .split(',')
    .map((code) => code.trim())
    .filter(Boolean)
    .map((code) => labels[code] || code)
})
const sourceQualities = computed(() => String(state.site.musicSourceQualities || '128k,320k,flac')
  .split(',')
  .map((item) => item.trim())
  .filter(Boolean))
const resolveQuality = computed(() => sourceQualities.value.includes('320k') ? '320k' : (sourceQualities.value[0] || '128k'))
const showPlayer = computed(() => state.site.musicEnabled === 'true' && Boolean(sourceUrl.value))
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
          url: '',
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
        pageUrl: track.pageUrl
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

function apiErrorMessage(error, fallback) {
  return error?.message || fallback
}

function togglePanel() {
  panelOpen.value = !panelOpen.value
  if (panelOpen.value) {
    if (!queue.value.length) view.value = 'search'
    nextTick(() => {
      if (!queue.value.length) searchInput.value?.focus()
    })
  } else {
    nextTick(() => fab.value?.focus())
  }
}

function closePanel() {
  panelOpen.value = false
  nextTick(() => fab.value?.focus())
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
  view.value = 'queue'
  playIndex(index, true)
}

function playIndex(index, autoplay = true) {
  const track = queue.value[index]
  if (!track) return
  currentIndex.value = index
  progress.value = 0
  duration.value = track.duration || 0
  if (track.url) {
    nextTick(() => startAudio(track, autoplay))
    return
  }
  resolveAndPlay(track, autoplay)
}

async function resolveAndPlay(track, autoplay = true) {
  const seq = ++playSeq
  loadingTrack.value = true
  track.urlLoading = true
  track.urlError = ''
  if (audio.value) {
    audio.value.pause()
  }
  playing.value = false
  try {
    const res = await http.get('/music/url', {
      params: {
        id: track.id,
        source: track.source || 'wy',
        quality: resolveQuality.value
      },
      timeout: 30000
    })
    if (seq !== playSeq) return
    if (currentIndex.value < 0 || queue.value[currentIndex.value]?.id !== track.id) return
    const url = res.data?.url
    if (!url) throw new Error('播放地址为空')
    track.url = url
    track.quality = res.data?.quality || resolveQuality.value
    track.urlLoading = false
    loadingTrack.value = false
    await nextTick()
    if (seq !== playSeq) return
    startAudio(track, autoplay)
  } catch (e) {
    if (seq !== playSeq) return
    track.urlLoading = false
    loadingTrack.value = false
    track.urlError = apiErrorMessage(e, '该歌曲暂时无法播放')
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
    element.play()
      .catch(() => {
        playing.value = false
        toast('浏览器阻止了自动播放，请再点一次播放', 'error')
      })
  }
}

function togglePlayback() {
  if (loadingTrack.value) return
  const track = currentTrack.value
  if (!track) {
    if (queue.value.length) playIndex(0, true)
    else {
      view.value = 'search'
      panelOpen.value = true
      nextTick(() => searchInput.value?.focus())
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
    element.play().catch(() => {
      playing.value = false
      toast('播放失败，请再点一次', 'error')
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
  if (!currentTrack.value?.url) return
  playing.value = false
  toast('音频加载失败，可能是版权限制或地址已失效', 'error')
}

function onKeydown(event) {
  if (event.key === 'Escape' && panelOpen.value) closePanel()
}

function onVisibilityChange() {
  if (document.hidden) {
    playing.value = audio.value ? !audio.value.paused : false
  }
}

onMounted(() => {
  loadPersistedState()
  if (audio.value) {
    audio.value.volume = volume.value
    audio.value.muted = muted.value
  }
  document.addEventListener('keydown', onKeydown)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
  searchSeq += 1
  playSeq += 1
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  audio.value?.pause()
})
</script>

<template>
  <div v-if="showPlayer" class="sm-app" :class="{ expanded: panelOpen }">
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
      <aside v-if="panelOpen" class="sm-panel" aria-label="站点音乐播放器">
        <header class="sm-head">
          <div class="sm-brand">
            <span class="sm-brand-mark">♪</span>
            <div>
              <strong>站点音乐</strong>
              <small>{{ sourceName }}<template v-if="sourceVersion"> · v{{ sourceVersion }}</template></small>
            </div>
          </div>
          <span class="sm-source-pill" :title="sourcePlatforms.join(' / ')">
            {{ sourcePlatforms.length ? sourcePlatforms.join(' · ') : 'LX 音源' }}
          </span>
          <button type="button" class="sm-icon-btn sm-close" aria-label="收起播放器" @click="closePanel">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m6 9 6 6 6-6" /></svg>
          </button>
        </header>

        <div class="sm-search">
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

        <nav class="sm-tabs" aria-label="播放器视图">
          <button type="button" :class="{ on: view === 'queue' }" @click="view = 'queue'">
            播放列表 <em>{{ queue.length }}</em>
          </button>
          <button type="button" :class="{ on: view === 'search' }" @click="view = 'search'">搜索</button>
        </nav>

        <div v-if="view === 'queue'" class="sm-body sm-queue-body">
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
            <p>搜索一首歌，或者从搜索结果里挑一首开始。</p>
            <button type="button" class="sm-empty-action" @click="view = 'search'">去搜索</button>
          </div>
        </div>

        <div v-else class="sm-body sm-search-body">
          <div v-if="searchHintVisible" class="sm-search-hint">
            <span class="sm-search-hint-kicker">SEARCH</span>
            <strong>想听点什么？</strong>
            <p>搜索网易云曲库，播放地址由 {{ sourceName }} 解析。</p>
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
            <p class="sm-result-more">搜索由网易云曲库提供 · 点击歌曲加入播放队列</p>
          </div>
        </div>

        <footer class="sm-foot">
          <span>{{ sourceName }}<template v-if="sourceVersion"> v{{ sourceVersion }}</template></span>
          <span v-if="currentTrack">{{ playing ? '正在播放' : '已暂停' }} · {{ currentTrack.title }}</span>
          <span v-else>搜索 / 队列 / 音量 / 播放模式</span>
        </footer>
      </aside>
    </transition>

    <div class="sm-bottom-row">
      <div v-if="!panelOpen && currentTrack" class="sm-mini">
        <button type="button" class="sm-mini-main" aria-label="打开播放器" @click="togglePanel">
          <span class="sm-mini-cover">
            <img v-if="currentTrack.cover" :src="currentTrack.cover" alt="" referrerpolicy="no-referrer" />
            <template v-else>{{ coverLetter(currentTrack) }}</template>
          </span>
          <span class="sm-mini-copy">
            <strong>{{ currentTrack.title }}</strong>
            <small>{{ currentTrack.artist }}</small>
          </span>
        </button>
        <button type="button" class="sm-mini-play" :aria-label="playing ? '暂停' : '播放'" @click="togglePlayback">
          <svg v-if="playing" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 5v14M17 5v14" /></svg>
          <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m8 5 11 7-11 7V5Z" /></svg>
        </button>
        <span class="sm-mini-eq" v-if="playing" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
      </div>

      <button
        ref="fab"
        type="button"
        class="sm-fab"
        :aria-label="panelOpen ? '收起播放器' : '打开站点音乐'"
        :title="panelOpen ? '收起播放器（音乐继续播放）' : '打开站点音乐'"
        @click="togglePanel"
      >
        <span class="sm-fab-art">
          <img v-if="currentTrack?.cover" :src="currentTrack.cover" alt="" referrerpolicy="no-referrer" />
          <template v-else>♪</template>
        </span>
        <span v-if="playing" class="sm-fab-eq" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
        <span class="sm-fab-ring" aria-hidden="true"></span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.sm-app {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 82;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  pointer-events: none;
}

.sm-panel {
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
.sm-brand > div { display: grid; gap: 2px; min-width: 0; }
.sm-brand strong { color: var(--ink); font-size: 14px; letter-spacing: .01em; }
.sm-brand small { overflow: hidden; color: var(--muted); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }
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

.sm-search-body { display: flex; flex-direction: column; gap: 10px; }
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

.sm-bottom-row { display: flex; align-items: center; gap: 8px; pointer-events: none; }
.sm-mini { display: flex; align-items: center; gap: 3px; max-width: 265px; padding: 5px 6px 5px 5px; border: 1px solid var(--line); border-radius: 999px; background: color-mix(in srgb, var(--paper) 88%, transparent); box-shadow: 0 9px 24px rgba(14, 35, 30, .14); backdrop-filter: blur(18px); pointer-events: auto; }
.sm-mini-main { display: flex; align-items: center; gap: 7px; min-width: 0; padding: 0; border: 0; background: transparent; color: var(--ink); text-align: left; cursor: pointer; }
.sm-mini-cover { display: grid; place-items: center; width: 33px; height: 33px; flex: 0 0 33px; overflow: hidden; border-radius: 50%; background: linear-gradient(145deg, var(--accent), #8d6bff); color: #fff; font-size: 13px; font-weight: 800; }
.sm-mini-cover img { width: 100%; height: 100%; object-fit: cover; }
.sm-mini-copy { display: grid; gap: 1px; min-width: 0; max-width: 148px; }
.sm-mini-copy strong { overflow: hidden; font-size: 10.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.sm-mini-copy small { overflow: hidden; color: var(--muted); font-size: 8.5px; text-overflow: ellipsis; white-space: nowrap; }
.sm-mini-play { display: grid; place-items: center; width: 28px; height: 28px; padding: 0; border: 0; border-radius: 50%; background: var(--accent); color: #fff; cursor: pointer; }
.sm-mini-play svg { width: 13px; height: 13px; }
.sm-mini-eq { display: none; }

.sm-fab { position: relative; display: grid; place-items: center; width: 54px; height: 54px; flex: 0 0 54px; padding: 0; border: 1px solid color-mix(in srgb, var(--accent) 24%, var(--line)); border-radius: 50%; background: color-mix(in srgb, var(--paper) 88%, transparent); box-shadow: 0 12px 30px rgba(20, 45, 40, .18), inset 0 1px 0 rgba(255, 255, 255, .7); backdrop-filter: blur(18px); cursor: pointer; pointer-events: auto; touch-action: manipulation; }
.sm-fab:hover { transform: translateY(-3px) scale(1.03); border-color: var(--accent); box-shadow: 0 16px 36px rgba(20, 45, 40, .22), 0 0 0 5px color-mix(in srgb, var(--accent) 10%, transparent); }
.sm-fab:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.sm-fab-art { display: grid; place-items: center; width: 40px; height: 40px; overflow: hidden; border-radius: 50%; background: linear-gradient(145deg, color-mix(in srgb, var(--accent) 72%, #fff), var(--accent)); color: #fff; font-size: 17px; font-weight: 800; box-shadow: inset 0 1px 0 rgba(255, 255, 255, .32), 0 5px 14px color-mix(in srgb, var(--accent) 20%, transparent); }
.sm-fab-art img { width: 100%; height: 100%; object-fit: cover; }
.sm-fab-eq { position: absolute; right: 4px; bottom: 4px; display: flex; align-items: flex-end; gap: 2px; width: 18px; height: 18px; padding: 3px; border-radius: 50%; background: var(--accent); box-shadow: 0 2px 8px color-mix(in srgb, var(--accent) 28%, transparent); }
.sm-fab-eq i { display: block; width: 2px; border-radius: 2px; background: #fff; animation: sm-bars 1s ease-in-out infinite alternate; }
.sm-fab-eq i:nth-child(1) { height: 5px; }
.sm-fab-eq i:nth-child(2) { height: 9px; animation-delay: .18s; }
.sm-fab-eq i:nth-child(3) { height: 7px; animation-delay: .34s; }
.sm-fab-eq i:nth-child(4) { height: 10px; animation-delay: .09s; }
.sm-fab-ring { position: absolute; inset: -4px; border: 1px solid color-mix(in srgb, var(--accent) 20%, transparent); border-radius: 50%; opacity: 0; animation: sm-pulse 2.2s ease-in-out infinite; }
.sm-app:not(.expanded) .sm-fab-ring { opacity: .32; }

.sm-panel-enter-active, .sm-panel-leave-active { transition: opacity .22s ease, transform .22s ease; }
.sm-panel-enter-from, .sm-panel-leave-to { opacity: 0; transform: translateY(10px) scale(.97); transform-origin: bottom right; }

@keyframes sm-bars { from { transform: scaleY(.55); opacity: .55; } to { transform: scaleY(1); opacity: 1; } }
@keyframes sm-spin { to { transform: rotate(360deg); } }
@keyframes sm-pulse { 0%, 100% { transform: scale(1); opacity: .32; } 50% { transform: scale(1.16); opacity: .08; } }

@media (max-width: 920px) {
  .sm-app { right: 12px; bottom: 84px; }
  .sm-panel { position: fixed; left: 12px; right: 12px; bottom: 84px; width: auto; max-width: none; max-height: calc(100dvh - 160px); border-radius: 20px; }
  .sm-mini { max-width: calc(100vw - 104px); }
  .sm-mini-copy { max-width: calc(100vw - 190px); }
}
@media (max-width: 520px) {
  .sm-panel { left: 0; right: 0; bottom: 78px; max-height: calc(100dvh - 145px); border-radius: 18px 18px 0 0; }
  .sm-head { padding: 12px 13px 10px; }
  .sm-source-pill { max-width: 90px; }
}
@media (prefers-reduced-motion: reduce) {
  .sm-fab-ring, .sm-now-playing b, .sm-mini-eq b, .sm-fab-eq i { animation: none; }
  .sm-panel-enter-active, .sm-panel-leave-active { transition: opacity .12s linear; }
}
</style>
