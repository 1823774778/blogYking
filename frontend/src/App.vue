<script setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavBar from './components/NavBar.vue'
import SiteMusic from './components/SiteMusic.vue'
import http from './api'
import { state, applyTheme, isAdmin } from './state'

const route = useRoute()
const router = useRouter()
const year = new Date().getFullYear()

const isAdminArea = computed(() => route.path.startsWith('/admin'))
const maintenanceMode = computed(() =>
  state.site.maintenanceEnabled === 'true'
  && !isAdminArea.value
  && !['/login', '/register', '/setup'].includes(route.path)
)
const readOnlyMode = computed(() => state.site.readOnlyMode === 'true' && !isAdminArea.value)
const showPublicChrome = computed(() =>
  !isAdminArea.value
  && !state.setupRequired
  && !maintenanceMode.value
)
const showFooter = computed(() => showPublicChrome.value)

function syncDocumentTitle() {
  const name = state.site?.siteName || '墨记'
  document.title = route.meta?.title ? `${route.meta.title} · ${name}` : name
}

watch(() => state.site.siteName, syncDocumentTitle)

function syncCustomHead(html) {
  document.getElementById('site-custom-head')?.remove()
  if (!html || !html.trim()) return
  const wrap = document.createElement('div')
  wrap.id = 'site-custom-head'
  wrap.innerHTML = html
  document.head.appendChild(wrap)
}

watch(() => state.site.customHead, (html) => syncCustomHead(html), { immediate: true })

onMounted(async () => {
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
      <router-link v-if="isAdmin()" class="btn" to="/admin">进入后台</router-link>
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

    <SiteMusic />

    <div class="toasts">
      <div v-for="t in state.toasts" :key="t.id" class="toast" :class="t.type">{{ t.message }}</div>
    </div>
  </div>
</template>
