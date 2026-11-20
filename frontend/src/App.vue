<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavBar from './components/NavBar.vue'
import http from './api'
import { state, applyTheme } from './state'

const route = useRoute()
const router = useRouter()
const year = new Date().getFullYear()

const showFooter = computed(
  () => route.path !== '/login' && route.path !== '/register' && route.path !== '/setup' && state.setupRequired === false
)

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
      document.title = `${res.data.siteName || '墨记'}`
    }
  } catch (e) {
    /* 站点信息加载失败不阻塞页面 */
  }
  if (state.token) {
    try {
      const res = await http.get('/auth/me')
      state.user = res.data
      localStorage.setItem('blog_user', JSON.stringify(res.data))
    } catch (e) {
      /* token 失效由拦截器处理 */
    }
  }
})
</script>

<template>
  <div class="app">
    <NavBar v-if="state.setupRequired === false" />
    <main class="shell main" :class="{ 'setup-main': state.setupRequired }">
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
        </div>
      </div>
    </footer>

    <nav v-if="showFooter" class="ff-dock" aria-label="移动端导航">
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
        :to="state.user.role === 'ADMIN' ? '/admin' : '/profile'"
        :class="{ active: route.path.startsWith('/admin') || route.path.startsWith('/profile') }"
      >
        <svg v-if="state.user.role === 'ADMIN'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <path d="M3 9h18M8 13h3M8 16h6" />
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <circle cx="12" cy="8" r="3.2" />
          <path d="M5 20a7 7 0 0 1 14 0" />
        </svg>
        {{ state.user.role === 'ADMIN' ? '后台' : '我的' }}
      </router-link>
      <router-link v-else to="/login" :class="{ active: route.path.startsWith('/login') }">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3" />
        </svg>
        登录
      </router-link>
    </nav>

    <div class="toasts">
      <div v-for="t in state.toasts" :key="t.id" class="toast" :class="t.type">{{ t.message }}</div>
    </div>
  </div>
</template>
