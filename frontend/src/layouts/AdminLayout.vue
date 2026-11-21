<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { clearAuth, state, toast } from '../state'
import { initialOf } from '../utils'
import ThemeMenu from '../components/ThemeMenu.vue'

const route = useRoute()
const router = useRouter()
const collapsed = ref(localStorage.getItem('admin_sidebar_collapsed') === '1')
const mobileOpen = ref(false)
const logoFailed = ref(false)
const showBrandLogo = computed(() => Boolean(state.site.logo) && !logoFailed.value)

watch(() => state.site.logo, () => {
  logoFailed.value = false
})

function onLogoError() {
  logoFailed.value = true
}

const sections = [
  { key: 'dashboard', label: '仪表盘', to: '/admin', icon: 'dashboard' },
  { key: 'profile', label: '个人信息', to: '/admin/profile', icon: 'profile' },
  { key: 'users', label: '用户管理', to: '/admin/users', icon: 'users' },
  { key: 'posts', label: '文章管理', to: '/admin/posts', icon: 'posts' },
  { key: 'comments', label: '评论管理', to: '/admin/comments', icon: 'comments' },
  { key: 'tags', label: '标签管理', to: '/admin/tags', icon: 'tags' },
  { key: 'site-info', label: '站点信息', to: '/admin/site-info', icon: 'site' },
  { key: 'notifications', label: '通知设置', to: '/admin/notifications', icon: 'notifications' },
  { key: 'storage', label: '存储管理', to: '/admin/storage', icon: 'storage' },
  { key: 'settings', label: '站点设置', to: '/admin/settings', icon: 'settings' }
]

const sectionTabs = {
  dashboard: [
    { key: 'overview', label: '数据概览' },
    { key: 'attention', label: '待处理' }
  ],
  profile: [
    { key: 'profile', label: '基本资料' },
    { key: 'security', label: '账号安全' }
  ],
  users: [
    { key: 'all', label: '全部用户' },
    { key: 'admins', label: '管理员' },
    { key: 'banned', label: '已封禁' },
    { key: 'muted', label: '已禁言' }
  ],
  posts: [
    { key: 'all', label: '全部文章' },
    { key: 'published', label: '已发布' },
    { key: 'draft', label: '草稿' },
    { key: 'pending', label: '待审核' },
    { key: 'rejected', label: '已退回' }
  ],
  comments: [
    { key: 'all', label: '全部评论' },
    { key: 'pending', label: '待审核' },
    { key: 'visible', label: '已展示' },
    { key: 'hidden', label: '已隐藏' }
  ],
  'site-info': [
    { key: 'basic', label: '基础信息' },
    { key: 'appearance', label: '品牌与关于' },
    { key: 'music', label: '站点音乐' },
    { key: 'footer', label: '页脚与联系' }
  ],
  tags: [
    { key: 'all', label: '标签目录' }
  ],
  notifications: [
    { key: 'all', label: '全部通知' },
    { key: 'published', label: '已发布' },
    { key: 'draft', label: '未发布' }
  ],
  storage: [
    { key: 'storage', label: '存储设置' }
  ],
  settings: [
    { key: 'smtp', label: 'SMTP' },
    { key: 'update', label: '站点更新' },
    { key: 'ai', label: 'AI 设置' },
    { key: 'registration', label: '注册设置' },
    { key: 'maintenance', label: '运行维护' }
  ]
}

const currentSection = computed(() => {
  const path = route.path
  if (path.startsWith('/admin/profile')) return sections.find((s) => s.key === 'profile')
  if (path.startsWith('/admin/users')) return sections.find((s) => s.key === 'users')
  if (path.startsWith('/admin/posts')) return sections.find((s) => s.key === 'posts')
  if (path.startsWith('/admin/comments')) return sections.find((s) => s.key === 'comments')
  if (path.startsWith('/admin/tags')) return sections.find((s) => s.key === 'tags')
  if (path.startsWith('/admin/site-info')) return sections.find((s) => s.key === 'site-info')
  if (path.startsWith('/admin/notifications')) return sections.find((s) => s.key === 'notifications')
  if (path.startsWith('/admin/storage')) return sections.find((s) => s.key === 'storage')
  if (path.startsWith('/admin/settings')) return sections.find((s) => s.key === 'settings')
  return sections[0]
})

const isEditor = computed(() => route.name === 'admin-post-new' || route.name === 'admin-post-edit')
const sectionLabel = computed(() => currentSection.value?.label || '仪表盘')
const displayName = computed(() => state.user?.nickname || state.user?.username || '站长')
const roleLabel = computed(() => (state.user?.role === 'ADMIN' ? '管理员' : '读者'))

const tabs = computed(() => sectionTabs[currentSection.value?.key] || [])
const activeTab = computed(() => {
  if (isEditor.value) return 'edit'
  if (route.name === 'admin-posts' && route.params.id) return 'edit'
  return String(route.query.tab || tabs.value[0]?.key || '')
})

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('admin_sidebar_collapsed', collapsed.value ? '1' : '0')
}

function collapseAction() {
  if (mobileOpen.value) {
    mobileOpen.value = false
    return
  }
  toggleCollapse()
}

const collapseLabel = computed(() => mobileOpen.value ? '关闭侧栏' : collapsed.value ? '展开侧栏' : '收起侧栏')
const sidebarToggleLabel = computed(() => mobileOpen.value ? '关闭侧边导航' : '打开侧边导航')

function goTab(tab) {
  if (isEditor.value) {
    router.push('/admin/posts')
    return
  }
  router.replace({ path: route.path, query: { ...route.query, tab: tab.key } })
}

async function logout() {
  try {
    await http.post('/auth/logout')
  } catch (e) {
    /* 本地退出仍然生效 */
  }
  clearAuth()
  toast('已退出登录')
  router.push('/')
}

function closeMobile() {
  mobileOpen.value = false
}

function onKeydown(e) {
  if (e.key === 'Escape') closeMobile()
}

watch(() => route.path, () => {
  mobileOpen.value = false
})
onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed }">
    <header class="admin-topbar">
      <router-link to="/" class="admin-brand" title="返回博客首页">
        <span class="admin-brand-mark" :class="{ 'has-image': showBrandLogo }">
          <img v-if="showBrandLogo" :src="state.site.logo" alt="" @error="onLogoError" />
          <svg v-else viewBox="0 0 32 32" fill="none">
            <ellipse cx="10.5" cy="11" rx="6.2" ry="9" transform="rotate(-38 10.5 11)" fill="currentColor" opacity=".28" />
            <ellipse cx="21.5" cy="11" rx="6.2" ry="9" transform="rotate(38 21.5 11)" fill="currentColor" opacity=".28" />
            <ellipse cx="10.5" cy="20" rx="4.8" ry="7" transform="rotate(-52 10.5 20)" fill="currentColor" opacity=".16" />
            <ellipse cx="21.5" cy="20" rx="4.8" ry="7" transform="rotate(52 21.5 20)" fill="currentColor" opacity=".16" />
            <circle cx="16" cy="16" r="2.6" fill="currentColor" />
            <path d="M15 13.8 13.8 11M17 13.8 18.2 11" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
          </svg>
        </span>
        <span v-if="!collapsed || mobileOpen" class="admin-brand-copy">
          <strong>{{ state.site.siteName || '墨记' }}</strong>
          <small>博客控制台</small>
        </span>
      </router-link>

      <div class="admin-topbar-main">
        <button class="admin-mobile-toggle" type="button" :aria-label="sidebarToggleLabel" :title="sidebarToggleLabel" @click="mobileOpen = !mobileOpen">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M4 7h16M4 12h16M4 17h16" />
          </svg>
        </button>

        <div class="admin-topbar-context">
          <span class="admin-kicker">ADMIN / {{ sectionLabel }}</span>
          <span class="admin-context-title">{{ isEditor ? (route.name === 'admin-post-edit' ? '编辑文章' : '新建文章') : sectionLabel }}</span>
        </div>

        <nav class="admin-subnav" aria-label="后台二级导航">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            type="button"
            :class="{ on: activeTab === tab.key && !isEditor }"
            @click="goTab(tab)"
          >{{ tab.label }}</button>
          <button v-if="isEditor" type="button" class="on" @click="goTab({ key: 'edit' })">文章编辑</button>
        </nav>

        <div class="admin-topbar-tools">
          <ThemeMenu />
        </div>
      </div>
    </header>

    <div v-if="mobileOpen" class="admin-sidebar-scrim" @click="closeMobile"></div>

    <aside class="admin-sidebar" :class="{ open: mobileOpen }">
      <nav class="admin-nav" aria-label="后台主导航">
        <router-link
          v-for="section in sections"
          :key="section.key"
          :to="section.to"
          class="admin-nav-item"
          :class="{ active: currentSection?.key === section.key }"
          :title="collapsed ? section.label : ''"
          :aria-label="section.label"
          @click="closeMobile"
        >
          <span class="admin-nav-icon" aria-hidden="true">
            <svg v-if="section.icon === 'dashboard'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <rect x="3" y="3" width="7" height="7" rx="1.5" />
              <rect x="14" y="3" width="7" height="7" rx="1.5" />
              <rect x="3" y="14" width="7" height="7" rx="1.5" />
              <rect x="14" y="14" width="7" height="7" rx="1.5" />
            </svg>
            <svg v-else-if="section.icon === 'profile'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <circle cx="12" cy="8" r="3.4" />
              <path d="M5 20a7 7 0 0 1 14 0" />
            </svg>
            <svg v-else-if="section.icon === 'users'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <circle cx="9" cy="8" r="3" />
              <path d="M3.5 19a5.5 5.5 0 0 1 11 0M16 5.5a3 3 0 0 1 0 5.6M17 14.5a5 5 0 0 1 3.5 4.5" />
            </svg>
            <svg v-else-if="section.icon === 'posts'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <path d="M5 3h10l4 4v14H5V3Z" />
              <path d="M15 3v4h4M8 12h8M8 16h6" />
            </svg>
            <svg v-else-if="section.icon === 'comments'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <path d="M4 5h16v11H9l-5 4V5Z" />
              <path d="M8 9h8M8 12h5" />
            </svg>
            <svg v-else-if="section.icon === 'site'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <circle cx="12" cy="12" r="9" />
              <path d="M3 12h18M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18" />
            </svg>
            <svg v-else-if="section.icon === 'tags'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <path d="M20 13 13 20a2 2 0 0 1-2.8 0L4 13.8V4h9.8L20 10.2a2 2 0 0 1 0 2.8Z" />
              <circle cx="8.5" cy="8.5" r="1.4" />
            </svg>
            <svg v-else-if="section.icon === 'storage'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <ellipse cx="12" cy="6" rx="8" ry="3" />
              <path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
            </svg>
            <svg v-else-if="section.icon === 'notifications'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
              <path d="M10 21h4" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-1.9 1.9-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V20h-2.6v-.1A1.7 1.7 0 0 0 11 18.4a1.7 1.7 0 0 0-1.9.3l-.1.1-1.9-1.9.1-.1A1.7 1.7 0 0 0 7.5 15a1.7 1.7 0 0 0-1.5-1H6v-2.6h.1A1.7 1.7 0 0 0 7.5 10a1.7 1.7 0 0 0-.3-1.9L7.1 8l1.9-1.9.1.1A1.7 1.7 0 0 0 11 6.5a1.7 1.7 0 0 0 1-1.5V5h2.6v.1A1.7 1.7 0 0 0 15.6 6.5a1.7 1.7 0 0 0 1.9-.3l.1-.1L19.5 8l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.5 1H21v2.6h-.1a1.7 1.7 0 0 0-1.5 1Z" />
            </svg>
          </span>
          <span v-if="!collapsed || mobileOpen" class="admin-nav-label">{{ section.label }}</span>
        </router-link>
      </nav>

      <div class="admin-sidebar-footer">
        <div class="admin-side-user">
          <button
            type="button"
            class="admin-user-avatar"
            :title="collapsed ? `${displayName} · 点击展开` : '个人信息'"
            :aria-label="collapsed ? `${displayName} · 点击展开侧栏` : '打开个人信息'"
            @click="collapsed ? toggleCollapse() : router.push('/admin/profile')"
          >
            <span class="avatar">
              <img v-if="state.user?.avatar" :src="state.user.avatar" alt="" />
              <template v-else>{{ initialOf(displayName) }}</template>
            </span>
          </button>
          <div v-if="!collapsed || mobileOpen" class="admin-user-copy">
            <strong>{{ displayName }}</strong>
            <small>{{ roleLabel }}</small>
          </div>
          <button v-if="!collapsed || mobileOpen" type="button" class="admin-logout" title="退出登录" @click="logout">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3" />
            </svg>
            <span>退出</span>
          </button>
        </div>

        <button type="button" class="admin-collapse" :title="collapseLabel" :aria-label="collapseLabel" @click="collapseAction">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path v-if="collapsed && !mobileOpen" d="M9 6l6 6-6 6" />
            <path v-else d="M15 6l-6 6 6 6" />
          </svg>
          <span v-if="!collapsed || mobileOpen">{{ collapseLabel }}</span>
        </button>
      </div>
    </aside>

    <main class="admin-content">
      <div class="admin-content-inner">
        <router-view />
      </div>
    </main>
  </div>
</template>
