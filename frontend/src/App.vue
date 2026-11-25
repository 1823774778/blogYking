<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavBar from './components/NavBar.vue'
import http from './api'
import { state, applyTheme, toast } from './state'
import { detectMusicPlatform, isAudioUrl, platformMeta, playlistEmbedUrl, playlistOpenUrl } from './utils/music'

const route = useRoute()
const router = useRouter()
const year = new Date().getFullYear()
const musicAudio = ref(null)
const musicPlaying = ref(false)
const musicPanelOpen = ref(false)
const musicCurrent = ref(0)
const musicDuration = ref(0)
const musicCloud = ref(null)
const musicLoading = ref(false)
const musicLoadError = ref('')
const musicTrackIndex = ref(0)
let musicRequestId = 0

const isAdminArea = computed(() => route.path.startsWith('/admin'))
const maintenanceMode = computed(() =>
  !isAdminArea.value
  && route.path !== '/login'
  && state.setupRequired === false
  && String(state.site.maintenanceEnabled ?? 'false').toLowerCase() === 'true'
)
const readOnlyMode = computed(() =>
  !isAdminArea.value
  && route.path !== '/login'
  && state.setupRequired === false
  && !maintenanceMode.value
  && String(state.site.readOnlyMode ?? 'false').toLowerCase() === 'true'
)
const showPublicChrome = computed(() => state.setupRequired === false && !isAdminArea.value && !maintenanceMode.value)
const showFooter = computed(
  () => showPublicChrome.value && route.path !== '/login' && route.path !== '/register' && route.path !== '/setup'
)
const musicLink = computed(() => String(state.site.musicPlaylistUrl || state.site.musicUrl || '').trim())
const musicPlatform = computed(() => platformMeta(
  musicCloud.value?.platform || state.site.musicPlaylistPlatform || detectMusicPlatform(musicLink.value)
))
const musicTracks = computed(() => Array.isArray(musicCloud.value?.tracks) ? musicCloud.value.tracks : [])
const musicCurrentTrack = computed(() => musicTracks.value[musicTrackIndex.value] || null)
const musicEmbedUrl = computed(() => musicPlatform.value.value === 'audio'
  ? ''
  : playlistEmbedUrl(musicPlatform.value.value, musicLink.value))
const musicOpenUrl = computed(() => playlistOpenUrl(musicPlatform.value.value, musicLink.value))
const musicTitle = computed(() => musicCloud.value?.title || state.site.musicPlaylistTitle || state.site.musicTitle || '站点歌单')
const musicCover = computed(() => musicCloud.value?.cover || musicCurrentTrack.value?.cover || state.site.musicCover || '')
const musicPlayableCount = computed(() => Number(musicCloud.value?.playableCount || 0))
const musicLoadedCount = computed(() => Number(musicCloud.value?.loadedCount || musicTracks.value.length))
const musicSubtitle = computed(() => {
  if (musicLoading.value) return '正在读取云端歌单…'
  if (musicTracks.value.length) {
    const playable = musicPlayableCount.value
    return `${musicPlatform.value.label} · ${playable > 0 ? `${playable} 首可播放` : '暂无可播曲目'}${musicLoadedCount.value > playable ? ` / 共 ${musicLoadedCount.value} 首` : ''}`
  }
  if (musicLoadError.value) return '云端歌单读取失败'
  if (musicCloud.value?.available === false) return `${musicPlatform.value.label} · 官方播放器`
  return `${musicPlatform.value.label} · 分享歌单`
})
const musicTone = computed(() => musicPlatform.value.tone)
const musicProgress = computed(() => (musicDuration.value > 0 ? (musicCurrent.value / musicDuration.value) * 100 : 0))
const showMusic = computed(
  () => showPublicChrome.value
    && state.site.musicEnabled === 'true'
    && Boolean(musicLink.value)
)

function formatMusicTime(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '00:00'
  const total = Math.floor(seconds)
  const min = String(Math.floor(total / 60)).padStart(2, '0')
  const sec = String(total % 60).padStart(2, '0')
  return `${min}:${sec}`
}

function directAudioFallback(link) {
  let title = '云端音频'
  try {
    const pathname = new URL(link, window.location.origin).pathname
    const fileName = pathname.split('/').pop() || ''
    title = decodeURIComponent(fileName.replace(/\.(mp3|m4a|aac|wav|ogg|flac|opus)$/i, '')) || title
  } catch {
    /* 保留默认名称 */
  }
  return {
    available: true,
    platform: 'audio',
    platformLabel: '云端音频',
    url: link,
    title,
    cover: '',
    loadedCount: 1,
    playableCount: 1,
    tracks: [{
      id: link,
      title,
      artist: '音频直链',
      album: '',
      cover: '',
      duration: 0,
      url: link,
      playable: true,
      pageUrl: link
    }]
  }
}

async function loadCloudMusic(force = false) {
  if (!musicLink.value) return
  if (!force && musicCloud.value?.url === musicLink.value && !musicLoadError.value) return
  const requestId = ++musicRequestId
  musicLoading.value = true
  musicLoadError.value = ''
  try {
    const res = await http.get('/music/playlist', {
      params: { url: musicLink.value },
      timeout: 30000
    })
    if (requestId !== musicRequestId) return
    musicCloud.value = res.data || {}
    const firstPlayable = (musicCloud.value.tracks || []).findIndex((track) => track.playable)
    musicTrackIndex.value = firstPlayable >= 0 ? firstPlayable : 0
    musicCurrent.value = 0
    musicDuration.value = 0
    await nextTick()
    musicAudio.value?.load()
  } catch (e) {
    if (requestId !== musicRequestId) return
    if (isAudioUrl(musicLink.value)) {
      musicCloud.value = directAudioFallback(musicLink.value)
      musicTrackIndex.value = 0
      musicLoadError.value = ''
    } else {
      musicCloud.value = null
      musicLoadError.value = e.message || '云端歌单读取失败，请检查链接'
    }
  } finally {
    if (requestId === musicRequestId) musicLoading.value = false
  }
}

function firstPlayableIndex(from = 0, step = 1) {
  const tracks = musicTracks.value
  if (!tracks.length) return -1
  let index = ((from % tracks.length) + tracks.length) % tracks.length
  for (let count = 0; count < tracks.length; count += 1) {
    if (tracks[index]?.playable && tracks[index]?.url) return index
    index = (index + step + tracks.length) % tracks.length
  }
  return -1
}

function selectMusicTrack(index, autoplay = true) {
  const track = musicTracks.value[index]
  if (!track) return
  if (!track.playable || !track.url) {
    toast('该曲目受版权限制，请在原平台打开收听', 'error')
    return
  }
  musicTrackIndex.value = index
  musicCurrent.value = 0
  musicDuration.value = 0
  musicPlaying.value = false
  nextTick(() => {
    const audio = musicAudio.value
    if (!audio) return
    audio.load()
    if (autoplay) {
      audio.play()
        .then(() => { musicPlaying.value = true })
        .catch(() => {
          musicPlaying.value = false
          toast('浏览器阻止了播放，请再点一次播放', 'error')
        })
    }
  })
}

function stepMusic(delta, autoplay = true) {
  if (!musicTracks.value.length) return
  const index = firstPlayableIndex(
    musicTrackIndex.value + delta,
    delta >= 0 ? 1 : -1
  )
  if (index < 0) {
    toast('这个歌单暂无可在线播放的曲目', 'error')
    return
  }
  selectMusicTrack(index, autoplay)
}

function toggleMusicPanel() {
  if (musicPanelOpen.value) {
    musicPanelOpen.value = false
    return
  }
  musicPanelOpen.value = true
  if (!musicCloud.value || musicCloud.value.url !== musicLink.value) loadCloudMusic()
}

function closeMusicPanel() {
  musicPanelOpen.value = false
}

function toggleMusicPlayback() {
  if (musicLoading.value) return
  if (!musicCurrentTrack.value) {
    const index = firstPlayableIndex()
    if (index >= 0) {
      selectMusicTrack(index, true)
      return
    }
    toast(musicLoadError.value || '暂无可播放曲目，请打开原歌单', 'error')
    return
  }
  const audio = musicAudio.value
  if (!audio) return
  if (audio.paused) {
    audio.play()
      .then(() => { musicPlaying.value = true })
      .catch(() => {
        musicPlaying.value = false
        toast('浏览器阻止了播放，请再点一次', 'error')
      })
  } else {
    audio.pause()
    musicPlaying.value = false
  }
}

function onMusicError() {
  musicPlaying.value = false
  toast('音频加载失败，可能是版权限制或链接已失效', 'error')
}

function onMusicEnded() {
  musicPlaying.value = false
  const next = firstPlayableIndex(musicTrackIndex.value + 1, 1)
  if (next >= 0 && next !== musicTrackIndex.value) selectMusicTrack(next, true)
}

function onDocumentKeydown(event) {
  if (event.key === 'Escape' && musicPanelOpen.value) musicPanelOpen.value = false
}

function onMusicTimeUpdate() {
  if (musicAudio.value) musicCurrent.value = musicAudio.value.currentTime || 0
}

function onMusicLoaded() {
  musicDuration.value = musicAudio.value?.duration || 0
}

function seekMusic(event) {
  const audio = musicAudio.value
  if (!audio) return
  const next = Number(event.target.value)
  audio.currentTime = (next / 100) * (musicDuration.value || 0)
  musicCurrent.value = audio.currentTime
}

function syncDocumentTitle() {
  const name = state.site?.siteName || '墨记'
  document.title = route.meta?.title ? `${route.meta.title} · ${name}` : name
}

watch(() => state.site.siteName, syncDocumentTitle)

watch(musicLink, () => {
  musicRequestId += 1
  musicPlaying.value = false
  musicCurrent.value = 0
  musicDuration.value = 0
  musicTrackIndex.value = 0
  musicCloud.value = null
  musicLoadError.value = ''
  if (musicAudio.value) musicAudio.value.pause()
  if (musicPanelOpen.value && musicLink.value) loadCloudMusic()
})

function syncCustomHead(html) {
  document.getElementById('site-custom-head')?.remove()
  if (!html || !html.trim()) return
  const wrap = document.createElement('div')
  wrap.id = 'site-custom-head'
  wrap.innerHTML = html
  document.head.appendChild(wrap)
}

watch(() => state.site.customHead, (html) => syncCustomHead(html), { immediate: true })

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onDocumentKeydown)
  musicRequestId += 1
  musicAudio.value?.pause()
})

onMounted(async () => {
  document.addEventListener('keydown', onDocumentKeydown)
  applyTheme(state.theme)

  // 首次启动：还没有管理员时，所有页面都先引导到初始化向导
  try {
    const res = await http.get('/setup/status')
    state.setupRequired = Boolean(res.data.required)
    if (state.setupRequired && route.path !== '/setup') {
      router.replace('/setup')
    }
  } catch (e) {
    /* 状态获取失败不阻塞页面 */
  }

  try {
    const res = await http.get('/meta/site')
    state.site = { ...state.site, ...res.data }
    if (!state.setupRequired) {
      syncDocumentTitle()
    }
  } catch (e) {
    /* 站点信息加载失败不阻塞页面 */
  }
  if (state.token || !state.user) {
    try {
      const res = await http.get('/auth/me')
      state.user = res.data
      localStorage.setItem('blog_user', JSON.stringify(res.data))
    } catch (e) {
      /* token / Cookie 失效由拦截器处理 */
    }
  }
})
</script>

<template>
  <div class="app" :class="{ 'is-admin': isAdminArea }">
    <div v-if="maintenanceMode" class="site-mode-page">
      <span class="hero-eyebrow">Maintenance</span>
      <h1>站点维护中</h1>
      <p>{{ state.site.maintenanceMessage || '站点正在维护升级，预计很快恢复。请稍后刷新页面。' }}</p>
      <router-link v-if="state.user?.role === 'ADMIN'" class="btn" to="/admin">进入后台</router-link>
      <router-link v-else class="btn" to="/login">管理员登录</router-link>
    </div>
    <div v-if="readOnlyMode" class="site-readonly-banner">
      <strong>只读模式</strong>
      <span>{{ state.site.readOnlyMessage || '站点当前为只读模式，浏览不受影响，暂时不能发布内容或评论。' }}</span>
    </div>
    <NavBar v-if="showPublicChrome" />
    <router-view v-if="isAdminArea" />
    <main v-else class="shell main" :class="{ 'setup-main': state.setupRequired }">
      <router-view />
    </main>

    <footer v-if="showFooter" class="footer">
      <div class="shell footer-inner">
        <span>© {{ year }} {{ state.site.siteName }} · {{ state.site.footer }}</span>
        <div class="footer-links">
          <router-link to="/about">关于</router-link>
          <a v-if="state.site.github" :href="state.site.github" target="_blank" rel="noopener">GitHub</a>
          <a v-if="state.site.email" :href="'mailto:' + state.site.email">邮箱</a>
          <a v-if="state.site.icp" href="https://beian.miit.gov.cn" target="_blank" rel="noopener">{{ state.site.icp }}</a>
          <a v-if="state.site.policeRecord" href="https://beian.mps.gov.cn/" target="_blank" rel="noopener">{{ state.site.policeRecord }}</a>
        </div>
      </div>
      <div v-if="state.site.customFooter" class="shell footer-custom" v-html="state.site.customFooter"></div>
    </footer>

    <nav v-if="showPublicChrome" class="ff-dock" aria-label="移动端导航">
      <router-link to="/" :class="{ active: route.path === '/' }">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="m3 10 9-7 9 7v9a2 2 0 0 1-2 2h-4v-6H9v6H5a2 2 0 0 1-2-2v-9Z" />
        </svg>
        首页
      </router-link>
      <router-link to="/about" :class="{ active: route.path === '/about' }">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 11v6M12 7.5v.5" />
        </svg>
        关于
      </router-link>
      <router-link
        v-if="state.user"
        to="/profile"
        :class="{ active: route.path.startsWith('/profile') }"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <circle cx="12" cy="8" r="3.2" />
          <path d="M5 20a7 7 0 0 1 14 0" />
        </svg>
        我的
      </router-link>
      <router-link v-else to="/login" :class="{ active: route.path.startsWith('/login') }">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3" />
        </svg>
        登录
      </router-link>
    </nav>

    <div
      v-if="showMusic"
      class="site-music"
      :class="{ playing: musicPlaying, expanded: musicPanelOpen }"
      :style="{ '--music-tone': musicTone }"
    >
      <audio
        ref="musicAudio"
        :src="musicCurrentTrack?.url || ''"
        preload="metadata"
        @play="musicPlaying = true"
        @pause="musicPlaying = false"
        @timeupdate="onMusicTimeUpdate"
        @loadedmetadata="onMusicLoaded"
        @ended="onMusicEnded"
        @error="onMusicError"
      ></audio>

      <transition name="music-panel">
        <aside v-if="musicPanelOpen" class="site-music-panel" aria-label="站点音乐播放器">
          <header class="site-music-head">
            <span class="site-music-cover" :class="{ 'has-image': musicCover }">
              <img v-if="musicCover" :src="musicCover" alt="" referrerpolicy="no-referrer" />
              <template v-else>{{ musicPlatform.mark }}</template>
            </span>
            <div class="site-music-copy">
              <strong>{{ musicTitle }}</strong>
              <span>{{ musicSubtitle }}</span>
            </div>
            <button type="button" class="site-music-close" aria-label="收起播放器" @click="closeMusicPanel">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="m6 9 6 6 6-6" />
              </svg>
            </button>
          </header>

          <div v-if="musicLoading" class="site-music-state">
            <span class="site-music-loader" aria-hidden="true"><i></i><i></i><i></i></span>
            <div>
              <strong>正在读取云端歌单</strong>
              <p>解析曲目与可播放状态，请稍候…</p>
            </div>
          </div>

          <div v-else-if="musicCurrentTrack" class="site-music-cloud">
            <div class="site-music-controls">
              <button type="button" class="site-music-step" aria-label="上一首" @click="stepMusic(-1)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M6 5v14M18 5 9 12l9 7V5Z" />
                </svg>
              </button>
              <button type="button" class="site-music-play" :aria-label="musicPlaying ? '暂停' : '播放'" @click="toggleMusicPlayback">
                <svg v-if="musicPlaying" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M7 5v14M17 5v14" />
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="m8 5 11 7-11 7V5Z" />
                </svg>
              </button>
              <button type="button" class="site-music-step" aria-label="下一首" @click="stepMusic(1)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M18 5v14M6 5l9 7-9 7V5Z" />
                </svg>
              </button>
              <div class="site-music-track">
                <div class="site-music-now">
                  <strong>{{ musicCurrentTrack.title }}</strong>
                  <span>{{ musicCurrentTrack.artist || '未知歌手' }}</span>
                </div>
                <input
                  type="range"
                  min="0"
                  max="100"
                  step="0.1"
                  :value="musicProgress"
                  aria-label="播放进度"
                  :style="{ '--music-progress': musicProgress + '%' }"
                  @input="seekMusic"
                />
                <div class="site-music-times">
                  <span>{{ formatMusicTime(musicCurrent) }}</span>
                  <span>{{ formatMusicTime(musicDuration || musicCurrentTrack.duration) }}</span>
                </div>
              </div>
            </div>

            <div class="site-music-tracklist" role="list" aria-label="歌单曲目">
              <button
                v-for="(track, index) in musicTracks"
                :key="track.id + '-' + index"
                type="button"
                role="listitem"
                class="site-music-track-row"
                :class="{ active: index === musicTrackIndex, unavailable: !track.playable }"
                @click="selectMusicTrack(index)"
              >
                <span class="site-music-track-index">
                  <span v-if="index === musicTrackIndex && musicPlaying" class="site-music-mini-eq" aria-hidden="true"><i></i><i></i><i></i></span>
                  <template v-else>{{ String(index + 1).padStart(2, '0') }}</template>
                </span>
                <span class="site-music-track-copy">
                  <strong>{{ track.title }}</strong>
                  <small>{{ track.artist || '未知歌手' }}<template v-if="!track.playable"> · 版权限制</template></small>
                </span>
                <span class="site-music-track-time">{{ track.duration ? formatMusicTime(track.duration) : '—' }}</span>
              </button>
            </div>

            <div class="site-music-list-foot">
              <span>{{ musicLoadedCount }} 首 · {{ musicPlayableCount }} 首可在线播放</span>
              <a v-if="musicOpenUrl" :href="musicOpenUrl" target="_blank" rel="noopener">打开原歌单 ↗</a>
            </div>
          </div>

          <div v-else class="site-music-playlist">
            <div v-if="musicLoadError" class="site-music-external is-error">
              <span class="site-music-external-orb">!</span>
              <div>
                <strong>歌单暂时无法读取</strong>
                <p>{{ musicLoadError }}</p>
              </div>
            </div>
            <template v-else>
              <p class="site-music-hint">点击歌单中的曲目开始播放，也可以前往原平台收听。</p>
              <iframe
                v-if="musicEmbedUrl"
                :src="musicEmbedUrl"
                class="site-music-frame"
                loading="lazy"
                allow="autoplay; encrypted-media"
                referrerpolicy="no-referrer-when-downgrade"
                title="平台歌单播放器"
              ></iframe>
              <div v-else class="site-music-external">
                <span class="site-music-external-orb">{{ musicPlatform.mark }}</span>
                <div>
                  <strong>{{ musicTitle }}</strong>
                  <p>{{ musicCloud?.message || `${musicPlatform.label}歌单暂不支持站内播放，点击下方按钮前往原平台收听。` }}</p>
                </div>
              </div>
            </template>
            <a v-if="musicOpenUrl" class="site-music-open" :href="musicOpenUrl" target="_blank" rel="noopener">
              在{{ musicPlatform.short }}打开歌单
              <span>↗</span>
            </a>
            <button v-if="musicLoadError" type="button" class="site-music-retry" @click="loadCloudMusic(true)">重新读取</button>
          </div>

          <footer class="site-music-foot">
            <span>{{ musicTracks.length ? '云端播放器' : '悬浮播放器' }}</span>
            <span v-if="musicPlaying">正在播放</span>
            <span v-else>{{ musicPlatform.label }}</span>
          </footer>
        </aside>
      </transition>

      <button type="button" class="site-music-btn" :aria-label="musicPanelOpen ? '收起站点音乐' : '打开站点音乐'" :title="musicPanelOpen ? '收起播放器（音乐继续播放）' : '打开站点音乐'" @click="toggleMusicPanel">
        <span class="site-music-fab-art">
          <img v-if="musicCover" :src="musicCover" alt="" referrerpolicy="no-referrer" />
          <template v-else>{{ musicPlatform.mark }}</template>
        </span>
        <span class="site-music-fab-eq" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
        <span class="site-music-fab-glow" aria-hidden="true"></span>
      </button>
    </div>

    <div class="toasts">
      <div v-for="t in state.toasts" :key="t.id" class="toast" :class="t.type">{{ t.message }}</div>
    </div>
  </div>
</template>
