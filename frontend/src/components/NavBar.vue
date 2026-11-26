<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { state, clearAuth, toast } from '../state'
import { formatDateTime, initialOf } from '../utils'
import ThemeMenu from './ThemeMenu.vue'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const menuRef = ref(null)
const logoFailed = ref(false)
const showSiteLogo = computed(() => Boolean(state.site.logo) && !logoFailed.value)
const defaultNavItems = [
  { label: '首页', path: '/', visible: true, newTab: false },
  { label: '关于', path: '/about', visible: true, newTab: false }
]
const navItems = computed(() => {
  let parsed
  try {
    parsed = JSON.parse(state.site.frontNavItems || '[]')
  } catch {
    parsed = []
  }
  if (!Array.isArray(parsed)) return defaultNavItems
  const visible = parsed
    .filter((item) => item && item.visible !== false && String(item.label || '').trim() && String(item.path || '').trim())
    .slice(0, 8)
    .map((item) => ({
      label: String(item.label).trim(),
      path: String(item.path).trim(),
      newTab: Boolean(item.newTab)
    }))
  return visible.length ? visible : defaultNavItems
})
const notifOpen = ref(false)
const notifRef = ref(null)
const notifications = ref([])
const notifLoading = ref(false)
const unreadCount = ref(0)
const searchOpen = ref(false)
const searchRef = ref(null)
const searchInput = ref(null)
const searchQuery = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
let searchTimer = null
let searchSeq = 0
let notifTimer = null

const seenNoticeId = () => Number(localStorage.getItem('blog_notice_seen') || 0)

watch(() => state.site.logo, () => {
  logoFailed.value = false
})

function onLogoError() {
  logoFailed.value = true
}

function isExternalPath(path) {
  return /^https?:\/\//i.test(String(path || ''))
}

function navActive(item) {
  if (isExternalPath(item.path)) return false
  if (item.path === '/') return route.path === '/'
  return route.path === item.path || route.path.startsWith(`${item.path}/`)
}

function navIcon(item) {
  if (item.path === '/') return 'home'
  if (item.path.startsWith('/about')) return 'about'
  return 'link'
}

async function logout() {
  try {
    await http.post('/auth/logout')
  } catch (e) {
    /* 本地退出仍然生效 */
  }
  clearAuth()
  menuOpen.value = false
  toast('已退出登录')
  router.push('/')
}

async function loadNotifications() {
  notifLoading.value = true
  try {
    notifications.value = (await http.get('/notifications', { params: { limit: 8 } })).data || []
    const seen = seenNoticeId()
    unreadCount.value = notifications.value.filter((n) => n.id > seen).length
  } catch {
    /* 通知失败不影响导航 */
  } finally {
    notifLoading.value = false
  }
}

function markNotificationsSeen() {
  const latest = notifications.value.reduce((max, n) => Math.max(max, n.id), 0)
  if (latest) localStorage.setItem('blog_notice_seen', String(latest))
  unreadCount.value = 0
}

function toggleNotifications() {
  notifOpen.value = !notifOpen.value
  searchOpen.value = false
  if (notifOpen.value) {
    menuOpen.value = false
    loadNotifications()
  } else {
    markNotificationsSeen()
  }
}

function toggleSearch() {
  searchOpen.value = !searchOpen.value
  notifOpen.value = false
  menuOpen.value = false
  if (searchOpen.value) {
    nextTick(() => searchInput.value?.focus())
  } else {
    searchQuery.value = ''
    searchResults.value = []
  }
}

function closeSearch() {
  searchOpen.value = false
  searchQuery.value = ''
  searchResults.value = []
}

async function runSearch(query) {
  const seq = ++searchSeq
  searchLoading.value = true
  try {
    const res = await http.get('/posts', { params: { keyword: query, size: 6, page: 1 } })
    if (seq !== searchSeq || !searchOpen.value) return
    searchResults.value = res.data.records || []
  } catch {
    if (seq === searchSeq) searchResults.value = []
  } finally {
    if (seq === searchSeq) searchLoading.value = false
  }
}

watch(searchQuery, (value) => {
  clearTimeout(searchTimer)
  const q = value.trim()
  if (!q) {
    searchResults.value = []
    searchLoading.value = false
    return
  }
  searchTimer = setTimeout(() => runSearch(q), 260)
})

function submitSearch() {
  const q = searchQuery.value.trim()
  if (!q) return
  if (searchResults.value.length) {
    goSearchResult(searchResults.value[0])
    return
  }
  router.push({ path: '/', query: { keyword: q } })
  closeSearch()
}

function goSearchResult(post) {
  router.push(`/post/${post.id}`)
  closeSearch()
}

function onDocumentClick(e) {
  if (menuRef.value && !menuRef.value.contains(e.target)) {
    menuOpen.value = false
  }
  if (notifRef.value && !notifRef.value.contains(e.target)) {
    if (notifOpen.value) markNotificationsSeen()
    notifOpen.value = false
  }
  if (searchRef.value && !searchRef.value.contains(e.target)) {
    searchOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  loadNotifications()
  notifTimer = setInterval(loadNotifications, 60000)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  clearTimeout(searchTimer)
  clearInterval(notifTimer)
})
</script>

<template>
  <header class="nav">
    <div class="shell nav-inner">
      <router-link to="/" class="logo">
        <span class="ff-logo-mark" :class="{ 'has-image': showSiteLogo }" aria-hidden="true">
          <img v-if="showSiteLogo" :src="state.site.logo" alt="" @error="onLogoError" />
          <svg v-else viewBox="0 0 32 32" fill="none">
            <ellipse cx="10.5" cy="11" rx="6.2" ry="9" transform="rotate(-38 10.5 11)" fill="currentColor" opacity=".28" />
            <ellipse cx="21.5" cy="11" rx="6.2" ry="9" transform="rotate(38 21.5 11)" fill="currentColor" opacity=".28" />
            <ellipse cx="10.5" cy="20" rx="4.8" ry="7" transform="rotate(-52 10.5 20)" fill="currentColor" opacity=".16" />
            <ellipse cx="21.5" cy="20" rx="4.8" ry="7" transform="rotate(52 21.5 20)" fill="currentColor" opacity=".16" />
            <circle cx="16" cy="16" r="2.6" fill="currentColor" />
            <path d="M15 13.8 13.8 11M17 13.8 18.2 11" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
          </svg>
        </span>
        <span class="logo-name">{{ state.site.siteName }}</span>
      </router-link>

      <nav class="nav-links" aria-label="顶部导航">
        <template v-for="(item, index) in navItems" :key="`${item.path}-${index}`">
          <a
            v-if="isExternalPath(item.path)"
            :href="item.path"
            :target="item.newTab ? '_blank' : undefined"
            :rel="item.newTab ? 'noopener noreferrer' : undefined"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M10 13a5 5 0 0 0 7.1 0l2.4-2.4a5 5 0 0 0-7.1-7.1L11 5" />
              <path d="M14 11a5 5 0 0 0-7.1 0L4.5 13.4a5 5 0 0 0 7.1 7.1L13 19" />
            </svg>
            {{ item.label }}
          </a>
          <router-link
            v-else
            :to="item.path"
            :target="item.newTab ? '_blank' : undefined"
            :rel="item.newTab ? 'noopener noreferrer' : undefined"
            :class="{ active: navActive(item) }"
          >
            <svg v-if="navIcon(item) === 'home'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="m3 10 9-7 9 7v9a2 2 0 0 1-2 2h-4v-6H9v6H5a2 2 0 0 1-2-2v-9Z" />
            </svg>
            <svg v-else-if="navIcon(item) === 'about'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 11v6M12 7.5v.5" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M10 13a5 5 0 0 0 7.1 0l2.4-2.4a5 5 0 0 0-7.1-7.1L11 5" />
              <path d="M14 11a5 5 0 0 0-7.1 0L4.5 13.4a5 5 0 0 0 7.1 7.1L13 19" />
            </svg>
            {{ item.label }}
          </router-link>
        </template>
      </nav>

      <div class="nav-right">
        <div ref="searchRef" class="nav-search-wrap" :class="{ open: searchOpen }">
          <div class="nav-search-field" :class="{ visible: searchOpen }">
            <input
              ref="searchInput"
              v-model="searchQuery"
              type="search"
              placeholder="搜索文章、摘要或标签…"
              aria-label="站内搜索"
              @keyup.enter="submitSearch"
              @keyup.esc="closeSearch"
            />
            <button v-if="searchQuery" class="nav-search-clear" type="button" aria-label="清空搜索" @click="searchQuery = ''">×</button>
          </div>
          <button class="icon-btn nav-search-toggle" type="button" :title="searchOpen ? '收起搜索' : '搜索'" @click.stop="toggleSearch">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-4-4" />
            </svg>
          </button>
          <div v-if="searchOpen && searchQuery.trim()" class="nav-search-drop" @click.stop>
            <div v-if="searchLoading" class="nav-drop-empty">正在搜索…</div>
            <button
              v-for="post in searchResults"
              v-else
              :key="post.id"
              class="nav-search-item"
              type="button"
              @click="goSearchResult(post)"
            >
              <strong>{{ post.title }}</strong>
              <span>{{ post.summary || '暂无摘要' }}</span>
              <em v-if="post.tags">{{ post.tags }}</em>
            </button>
            <div v-if="!searchLoading && !searchResults.length" class="nav-drop-empty">没有找到相关内容</div>
            <button v-if="searchResults.length" class="nav-search-all" type="button" @click="submitSearch">查看全部搜索结果 →</button>
          </div>
        </div>

        <div ref="notifRef" class="notif-wrap">
          <button class="icon-btn notif-toggle" type="button" :title="notifOpen ? '收起通知' : '通知'" @click.stop="toggleNotifications">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
              <path d="M10 21h4" />
            </svg>
            <span v-if="unreadCount" class="notif-dot">{{ unreadCount > 9 ? '9+' : unreadCount }}</span>
          </button>
          <div v-if="notifOpen" class="notif-menu" @click.stop>
            <div class="notif-head">
              <strong>通知</strong>
              <button type="button" class="btn-text" @click="loadNotifications">刷新</button>
            </div>
            <div v-if="notifLoading" class="nav-drop-empty">正在读取通知…</div>
            <div v-else-if="!notifications.length" class="nav-drop-empty">暂时没有新通知</div>
            <div v-else class="notif-list">
              <article v-for="item in notifications" :key="item.id" class="notif-item" :class="`is-${String(item.type).toLowerCase()}`">
                <div class="notif-item-head">
                  <span class="notif-type">{{ item.type === 'UPDATE' ? '更新' : item.type === 'SYSTEM' ? '系统' : '通知' }}</span>
                  <span v-if="item.pinned" class="notif-pin">置顶</span>
                  <time>{{ formatDateTime(item.createdAt) }}</time>
                </div>
                <strong>{{ item.title }}</strong>
                <p>{{ item.content }}</p>
              </article>
            </div>
          </div>
        </div>

        <ThemeMenu />

        <div v-if="state.user" ref="menuRef" class="user-menu-wrap">
          <button class="user-trigger" type="button" @click="menuOpen = !menuOpen">
            <span class="avatar" style="width: 32px; height: 32px; font-size: 14px">
              <img v-if="state.user.avatar" :src="state.user.avatar" alt="" />
              <template v-else>{{ initialOf(state.user.nickname || state.user.username) }}</template>
            </span>
          </button>
          <div v-if="menuOpen" class="user-menu user-menu-compact">
            <router-link v-if="state.user.role === 'ADMIN'" to="/admin" @click="menuOpen = false">后台管理</router-link>
            <button type="button" @click="logout">退出登录</button>
          </div>
        </div>

        <router-link v-else to="/login" class="btn btn-ghost btn-sm">登录</router-link>
      </div>
    </div>
  </header>
</template>
