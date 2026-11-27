<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { state, clearAuth, toast, isAdmin } from '../state'
import { formatDateTime, initialOf } from '../utils'
import ThemeMenu from './ThemeMenu.vue'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const menuRef = ref(null)
const mobileNavOpen = ref(false)
const mobileNavRef = ref(null)
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

watch(() => route.fullPath, () => {
  mobileNavOpen.value = false
  menuOpen.value = false
})

function onKeydown(e) {
  if (e.key === 'Escape') {
    mobileNavOpen.value = false
    menuOpen.value = false
  }
}

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

function toggleMobileNav() {
  mobileNavOpen.value = !mobileNavOpen.value
  if (mobileNavOpen.value) {
    menuOpen.value = false
    if (notifOpen.value) markNotificationsSeen()
    notifOpen.value = false
    closeSearch()
  }
}

function closeMobileNav() {
  mobileNavOpen.value = false
}

async function logout() {
  try {
    await http.post('/auth/logout')
  } catch (e) {
    /* 本地退出仍然生效 */
  }
  clearAuth()
  menuOpen.value = false
  mobileNavOpen.value = false
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
  mobileNavOpen.value = false
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
  mobileNavOpen.value = false
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
  if (mobileNavRef.value && !mobileNavRef.value.contains(e.target)) {
    mobileNavOpen.value = false
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
  document.addEventListener('keydown', onKeydown)
  loadNotifications()
  notifTimer = setInterval(loadNotifications, 60000)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
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
            <router-link v-if="isAdmin()" to="/admin" @click="menuOpen = false">后台管理</router-link>
            <button type="button" @click="logout">退出登录</button>
          </div>
        </div>

        <router-link v-else to="/login" class="btn btn-ghost btn-sm">登录</router-link>

        <div ref="mobileNavRef" class="nav-mobile-wrap">
          <button
            class="nav-mobile-toggle"
            type="button"
            aria-controls="nav-mobile-menu"
            :aria-expanded="mobileNavOpen ? 'true' : 'false'"
            :title="mobileNavOpen ? '收起导航菜单' : '打开导航菜单'"
            @click.stop="toggleMobileNav"
          >
            <span class="nav-mobile-bars" :class="{ open: mobileNavOpen }" aria-hidden="true">
              <i></i><i></i><i></i>
            </span>
          </button>

          <transition name="nav-mobile-drop">
            <nav v-if="mobileNavOpen" id="nav-mobile-menu" class="nav-mobile-menu" aria-label="移动端导航菜单" @click.stop>
              <div class="nav-mobile-head">
                <span>站点导航</span>
                <small>MENU</small>
              </div>

              <div class="nav-mobile-links">
                <template v-for="(item, index) in navItems" :key="`mobile-${item.path}-${index}`">
                  <a
                    v-if="isExternalPath(item.path)"
                    :href="item.path"
                    :target="item.newTab ? '_blank' : undefined"
                    :rel="item.newTab ? 'noopener noreferrer' : undefined"
                    @click="closeMobileNav"
                  >
                    <span class="nav-mobile-link-icon">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                        <path d="M10 13a5 5 0 0 0 7.1 0l2.4-2.4a5 5 0 0 0-7.1-7.1L11 5" />
                        <path d="M14 11a5 5 0 0 0-7.1 0L4.5 13.4a5 5 0 0 0 7.1 7.1L13 19" />
                      </svg>
                    </span>
                    <span>{{ item.label }}</span>
                    <svg class="nav-mobile-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                      <path d="m9 5 7 7-7 7" />
                    </svg>
                  </a>
                  <router-link
                    v-else
                    :to="item.path"
                    :target="item.newTab ? '_blank' : undefined"
                    :rel="item.newTab ? 'noopener noreferrer' : undefined"
                    :class="{ active: navActive(item) }"
                    @click="closeMobileNav"
                  >
                    <span class="nav-mobile-link-icon">
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
                    </span>
                    <span>{{ item.label }}</span>
                    <svg class="nav-mobile-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                      <path d="m9 5 7 7-7 7" />
                    </svg>
                  </router-link>
                </template>
              </div>

              <div v-if="state.user" class="nav-mobile-account">
                <router-link to="/profile" class="nav-mobile-user" @click="closeMobileNav">
                  <span class="avatar nav-mobile-avatar">
                    <img v-if="state.user.avatar" :src="state.user.avatar" alt="" />
                    <template v-else>{{ initialOf(state.user.nickname || state.user.username) }}</template>
                  </span>
                  <span class="nav-mobile-user-copy">
                    <strong>{{ state.user.nickname || state.user.username }}</strong>
                    <small>个人主页</small>
                  </span>
                  <svg class="nav-mobile-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="m9 5 7 7-7 7" />
                  </svg>
                </router-link>
                <div class="nav-mobile-actions">
                  <router-link v-if="isAdmin()" to="/admin" @click="closeMobileNav">后台管理</router-link>
                  <button type="button" @click="logout">退出登录</button>
                </div>
              </div>

              <router-link v-else to="/login" class="nav-mobile-login" @click="closeMobileNav">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                  <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3" />
                </svg>
                登录 / 注册
              </router-link>
            </nav>
          </transition>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped>
.nav-mobile-wrap {
  display: none;
}

@media (max-width: 760px) {
  .nav-mobile-wrap {
    display: block;
  }

  .nav-mobile-toggle {
    width: 34px;
    height: 34px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 0;
    border: 0;
    border-radius: 9px;
    background: transparent;
    color: var(--ink);
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
    transition: background .18s ease;
  }

  .nav-mobile-toggle:hover {
    background: color-mix(in srgb, var(--ink) 7%, transparent);
  }

  .nav-mobile-toggle:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }

  .nav-mobile-bars {
    position: relative;
    display: block;
    width: 18px;
    height: 14px;
    transform-origin: center;
    transition: transform .34s cubic-bezier(.22, 1, .36, 1);
  }

  .nav-mobile-bars i {
    position: absolute;
    left: 0;
    width: 18px;
    height: 2px;
    border-radius: 999px;
    background: currentColor;
  }

  .nav-mobile-bars i:nth-child(1) {
    top: 0;
  }

  .nav-mobile-bars i:nth-child(2) {
    top: 6px;
  }

  .nav-mobile-bars i:nth-child(3) {
    top: 12px;
  }

  .nav-mobile-bars.open {
    transform: rotate(90deg);
  }

  .nav-mobile-menu {
    position: absolute;
    top: calc(100% + 8px);
    left: 10px;
    right: 10px;
    z-index: 80;
    max-height: calc(100dvh - 86px);
    overflow-y: auto;
    padding: 8px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--ff-card-solid, var(--paper));
    box-shadow: 0 20px 52px rgba(26, 43, 34, .17);
    backdrop-filter: blur(18px);
    -webkit-overflow-scrolling: touch;
  }

  .nav-mobile-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 12px 9px;
  }

  .nav-mobile-head span {
    color: var(--ink);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: .08em;
  }

  .nav-mobile-head small {
    color: var(--muted);
    font-size: 10px;
    font-weight: 600;
    letter-spacing: .16em;
  }

  .nav-mobile-links {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .nav-mobile-links a {
    display: flex;
    align-items: center;
    gap: 11px;
    min-height: 45px;
    padding: 9px 12px;
    border-radius: 10px;
    color: var(--ink-soft);
    font-size: 14px;
    font-weight: 600;
  }

  .nav-mobile-links a.active {
    background: color-mix(in srgb, var(--accent) 10%, transparent);
    color: var(--accent-ink, var(--accent));
  }

  .nav-mobile-links a:active {
    background: color-mix(in srgb, var(--accent) 14%, transparent);
  }

  .nav-mobile-link-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    border-radius: 8px;
    background: color-mix(in srgb, var(--ink) 6%, transparent);
    color: var(--muted);
  }

  .nav-mobile-links a.active .nav-mobile-link-icon {
    background: color-mix(in srgb, var(--accent) 14%, transparent);
    color: var(--accent-ink, var(--accent));
  }

  .nav-mobile-link-icon svg {
    width: 17px;
    height: 17px;
  }

  .nav-mobile-arrow {
    width: 16px;
    height: 16px;
    margin-left: auto;
    color: var(--muted);
    opacity: .62;
  }

  .nav-mobile-account {
    margin-top: 7px;
    padding-top: 9px;
    border-top: 1px solid var(--line);
  }

  .nav-mobile-user {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 10px;
    border-radius: 11px;
    color: var(--ink);
  }

  .nav-mobile-user:active {
    background: color-mix(in srgb, var(--ink) 5%, transparent);
  }

  .nav-mobile-avatar {
    width: 34px;
    height: 34px;
    flex: 0 0 34px;
    font-size: 14px;
  }

  .nav-mobile-user-copy {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 1px;
  }

  .nav-mobile-user-copy strong {
    overflow: hidden;
    color: var(--ink);
    font-size: 13.5px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .nav-mobile-user-copy small {
    color: var(--muted);
    font-size: 11px;
  }

  .nav-mobile-actions {
    display: flex;
    gap: 7px;
    margin-top: 5px;
  }

  .nav-mobile-actions a,
  .nav-mobile-actions button {
    flex: 1;
    min-height: 36px;
    padding: 0 10px;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: transparent;
    color: var(--ink-soft);
    font: inherit;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
  }

  .nav-mobile-actions a {
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  .nav-mobile-actions button {
    font-family: inherit;
  }

  .nav-mobile-actions a:active,
  .nav-mobile-actions button:active {
    background: color-mix(in srgb, var(--ink) 5%, transparent);
  }

  .nav-mobile-login {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 42px;
    margin-top: 8px;
    border-radius: 10px;
    background: var(--accent);
    color: #fff;
    font-size: 13px;
    font-weight: 650;
  }

  .nav-mobile-login svg {
    width: 17px;
    height: 17px;
  }
}

.nav-mobile-drop-enter-active,
.nav-mobile-drop-leave-active {
  transition: opacity .2s ease, transform .26s cubic-bezier(.22, 1, .36, 1);
}

.nav-mobile-drop-enter-from,
.nav-mobile-drop-leave-to {
  opacity: 0;
  transform: translateY(-10px) scale(.985);
}
</style>
