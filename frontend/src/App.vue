<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavBar from './components/NavBar.vue'
import http from './api'
import { state, applyTheme, applyThemePreset, toast } from './state'
import { detectMusicPlatform, platformMeta, playlistEmbedUrl, playlistOpenUrl } from './utils/music'

const route = useRoute()
const router = useRouter()
const year = new Date().getFullYear()
const musicAudio = ref(null)
const musicPlaying = ref(false)
const musicPanelOpen = ref(false)
const musicCurrent = ref(0)
const musicDuration = ref(0)

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
const musicSource = computed(() => (state.site.musicSource === 'playlist' ? 'playlist' : 'audio'))
const musicPlatform = computed(() => platformMeta(state.site.musicPlaylistPlatform || detectMusicPlatform(state.site.musicPlaylistUrl)))
const musicPlaylistUrl = computed(() => state.site.musicPlaylistUrl || '')
const musicEmbedUrl = computed(() => playlistEmbedUrl(state.site.musicPlaylistPlatform, state.site.musicPlaylistUrl))
const musicOpenUrl = computed(() => playlistOpenUrl(state.site.musicPlaylistPlatform, state.site.musicPlaylistUrl))
const musicTitle = computed(() => musicSource.value === 'playlist'
  ? (state.site.musicPlaylistTitle || state.site.musicTitle || '站点歌单')
  : (state.site.musicTitle || '站点音乐'))
const musicSubtitle = computed(() => musicSource.value === 'playlist'
  ? `${musicPlatform.value.label} · 分享歌单`
  : (state.site.musicArtist || '单曲音频'))
const musicTone = computed(() => musicSource.value === 'playlist' ? musicPlatform.value.tone : '#2f6fed')
const musicProgress = computed(() => (musicDuration.value > 0 ? (musicCurrent.value / musicDuration.value) * 100 : 0))
const showMusic = computed(
  () => showPublicChrome.value
    && state.site.musicEnabled === 'true'
    && (musicSource.value === 'playlist' ? Boolean(musicPlaylistUrl.value) : Boolean(state.site.musicUrl))
)

function formatMusicTime(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '00:00'
  const total = Math.floor(seconds)
  const min = String(Math.floor(total / 60)).padStart(2, '0')
  const sec = String(total % 60).padStart(2, '0')
  return `${min}:${sec}`
}

function toggleMusicPanel() {
  musicPanelOpen.value = !musicPanelOpen.value
  if (musicSource.value === 'playlist') return
  if (!musicPanelOpen.value && musicPlaying.value) {
    musicAudio.value?.pause()
  }
}

function toggleMusic() {
  if (musicSource.value === 'playlist') {
    musicPanelOpen.value = !musicPanelOpen.value
    return
  }
  const audio = musicAudio.value
  if (!audio) return
  if (!musicPanelOpen.value) musicPanelOpen.value = true
  if (audio.paused) {
    audio.play()
      .then(() => { musicPlaying.value = true })
      .catch(() => toast('浏览器阻止了播放，请再点一次', 'error'))
  } else {
    audio.pause()
    musicPlaying.value = false
  }
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

watch(() => [state.site.musicUrl, state.site.musicPlaylistUrl, state.site.musicSource], () => {
  musicPlaying.value = false
  musicCurrent.value = 0
  musicDuration.value = 0
  if (musicAudio.value) musicAudio.value.load()
})

function syncCustomHead(html) {
  document.getElementById('site-custom-head')?.remove()
  if (!html || !html.trim()) return
  const wrap = document.createElement('div')
  wrap.id = 'site-custom-head'
  wrap.innerHTML = html
  document.head.appendChild(wrap)
}

watch(() => state.site.themePreset, (preset) => applyThemePreset(preset))
watch(() => state.site.customHead, (html) => syncCustomHead(html), { immediate: true })

onMounted(async () => {
  applyTheme(state.theme)
  applyThemePreset(state.site.themePreset)

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
      :class="{ playing: musicPlaying, expanded: musicPanelOpen, 'is-playlist': musicSource === 'playlist' }"
      :style="{ '--music-tone': musicTone }"
    >
      <audio
        v-if="musicSource === 'audio'"
        ref="musicAudio"
        :src="state.site.musicUrl"
        preload="none"
        loop
        @play="musicPlaying = true"
        @pause="musicPlaying = false"
        @timeupdate="onMusicTimeUpdate"
        @loadedmetadata="onMusicLoaded"
      ></audio>

      <transition name="music-panel">
        <aside v-if="musicPanelOpen" class="site-music-panel" aria-label="站点音乐播放器">
          <header class="site-music-head">
            <span class="site-music-cover" :class="{ 'has-image': state.site.musicCover }">
              <img v-if="state.site.musicCover" :src="state.site.musicCover" alt="" />
              <template v-else>{{ musicSource === 'playlist' ? musicPlatform.mark : '♪' }}</template>
            </span>
            <div class="site-music-copy">
              <strong>{{ musicTitle }}</strong>
              <span>{{ musicSubtitle }}</span>
            </div>
            <button type="button" class="site-music-close" aria-label="收起播放器" @click="toggleMusicPanel">×</button>
          </header>

          <div v-if="musicSource === 'audio'" class="site-music-controls">
            <button type="button" class="site-music-play" :aria-label="musicPlaying ? '暂停' : '播放'" @click="toggleMusic">
              <svg v-if="musicPlaying" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M7 5v14M17 5v14" />
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="m8 5 11 7-11 7V5Z" />
              </svg>
            </button>
            <div class="site-music-track">
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
                <span>{{ formatMusicTime(musicDuration) }}</span>
              </div>
            </div>
            <span class="site-music-eq" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
          </div>

          <div v-else class="site-music-playlist">
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
                <p>{{ musicPlatform.label }}歌单不支持内嵌播放，点击下方按钮前往原平台收听。</p>
              </div>
            </div>
            <a class="site-music-open" :href="musicOpenUrl" target="_blank" rel="noopener">
              在{{ musicPlatform.short }}打开歌单
              <span>↗</span>
            </a>
          </div>

          <footer class="site-music-foot">
            <span>悬浮播放器</span>
            <span v-if="musicSource === 'audio'">{{ musicPlaying ? '正在播放' : '已暂停' }}</span>
            <span v-else>{{ musicPlatform.label }}</span>
          </footer>
        </aside>
      </transition>

      <button type="button" class="site-music-btn" :aria-label="musicPanelOpen ? '收起站点音乐' : '打开站点音乐'" @click="toggleMusic">
        <span class="site-music-fab-art">
          <img v-if="state.site.musicCover" :src="state.site.musicCover" alt="" />
          <template v-else>{{ musicSource === 'playlist' ? musicPlatform.mark : '♪' }}</template>
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
