<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { clearAuth, hasAnyPermission, hasPermission, state, toast } from '../state'
import { initialOf } from '../utils'
import ThemeMenu from '../components/ThemeMenu.vue'
import AdminNavIcon from '../components/AdminNavIcon.vue'

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
  { key: 'profile', label: '个人中心', to: '/admin/profile', icon: 'profile', permissions: ['system:profile'] },
  { key: 'my-posts', label: '我的文章', to: '/admin/my-posts', icon: 'posts', permissions: ['system:my-posts'] },
  { key: 'my-comments', label: '我的评论', to: '/admin/my-comments', icon: 'comments', permissions: ['system:my-comments'] },
  { key: 'manage-posts', label: '文章管理', to: '/admin/manage/posts', icon: 'posts', permissions: ['system:posts'] },
  { key: 'manage-comments', label: '评论管理', to: '/admin/manage/comments', icon: 'comments', permissions: ['system:comments'] },
  { key: 'manage-tags', label: '标签管理', to: '/admin/manage/tags', icon: 'tags', permissions: ['system:tags'] },
  { key: 'manage-announcements', label: '公告管理', to: '/admin/manage/announcements', icon: 'announcements', permissions: ['system:announcements'] },
  { key: 'manage-navigation', label: '导航管理', to: '/admin/manage/navigation', icon: 'navigation', permissions: ['system:navigation'] },
  { key: 'manage-users', label: '用户管理', to: '/admin/manage/users', icon: 'users', permissions: ['system:users'] },
  { key: 'manage-storage', label: '存储管理', to: '/admin/manage/storage', icon: 'storage', permissions: ['system:storage'] },
  { key: 'roles', label: '角色权限', to: '/admin/roles', icon: 'roles', permissions: ['system:roles'] },
  { key: 'site-info', label: '站点信息', to: '/admin/site-info', icon: 'site', permissions: ['system:site'] },
  { key: 'notifications', label: '通知设置', to: '/admin/notifications', icon: 'notifications', permissions: ['system:notifications'] },
  { key: 'settings', label: '站点设置', to: '/admin/settings', icon: 'settings', permissions: ['system:settings'] }
]

function sectionByKey(key) {
  return sections.find((item) => item.key === key)
}

function visibleSection(section) {
  return section && (!section.permissions || hasAnyPermission(section.permissions))
}

const visibleSections = computed(() => sections.filter(visibleSection))
const primarySections = computed(() => ['dashboard', 'profile']
  .map(sectionByKey)
  .filter(visibleSection))
const canManageContent = computed(() =>
  hasPermission('system:posts') || hasPermission('system:comments'))
const showMyPosts = computed(() => hasPermission('system:my-posts')
  && (state.user?.role === 'ADMIN' || !hasPermission('system:posts')))
const showMyComments = computed(() => hasPermission('system:my-comments')
  && (state.user?.role === 'ADMIN' || !hasPermission('system:comments')))
const navGroups = computed(() => [
  {
    key: 'mine',
    label: '内容',
    hint: 'CONTENT',
    icon: 'profile',
    items: [
      ...(showMyPosts.value ? [sectionByKey('my-posts')] : []),
      ...(showMyComments.value ? [sectionByKey('my-comments')] : [])
    ].filter(visibleSection)
  },
  {
    key: 'manage',
    label: '管理',
    hint: 'MANAGEMENT',
    icon: 'posts',
    items: [
      'manage-posts', 'manage-comments', 'manage-tags', 'manage-announcements',
      'manage-navigation', 'manage-users', 'manage-storage'
    ].map(sectionByKey).filter(visibleSection)
  },
  {
    key: 'settings',
    label: '设置',
    hint: 'SETTINGS',
    icon: 'settings',
    items: ['roles', 'site-info', 'notifications', 'settings']
      .map(sectionByKey)
      .filter(visibleSection)
  }
].filter((group) => group.items.length))
const groupedSections = computed(() => navGroups.value.flatMap((group) => group.items))
const savedOpenGroups = (() => {
  try {
    return JSON.parse(localStorage.getItem('admin_nav_groups') || '{}') || {}
  } catch {
    return {}
  }
})()
const openGroups = ref({
  mine: savedOpenGroups.mine !== false,
  manage: savedOpenGroups.manage !== false,
  settings: savedOpenGroups.settings !== false
})
function toggleNavGroup(key) {
  openGroups.value = { ...openGroups.value, [key]: !openGroups.value[key] }
  try {
    localStorage.setItem('admin_nav_groups', JSON.stringify(openGroups.value))
  } catch {
    /* ignore storage failures */
  }
}
function groupHasActive(group) {
  return group.items.some((item) => item.key === currentSection.value?.key)
}

const sectionTabs = {
  dashboard: [
    { key: 'overview', label: '数据概览' },
    { key: 'attention', label: '待处理' }
  ],
  profile: [
    { key: 'profile', label: '基本资料' },
    { key: 'security', label: '账号安全' }
  ],
  'manage-posts': [
    { key: 'all', label: '全部文章' },
    { key: 'published', label: '已发布' },
    { key: 'draft', label: '草稿' },
    { key: 'pending', label: '待审核' },
    { key: 'rejected', label: '已退回' }
  ],
  'manage-comments': [
    { key: 'all', label: '全部评论' },
    { key: 'pending', label: '待审核' },
    { key: 'visible', label: '已展示' },
    { key: 'hidden', label: '已隐藏' }
  ],
  'manage-users': [
    { key: 'all', label: '全部用户' },
    { key: 'admins', label: '管理员' },
    { key: 'banned', label: '已封禁' },
    { key: 'muted', label: '已禁言' }
  ],
  'manage-tags': [],
  'manage-announcements': [],
  'manage-navigation': [],
  'manage-storage': [],
  roles: [
    { key: 'all', label: '角色列表' },
    { key: 'permissions', label: '权限目录' }
  ],
  'my-posts': [
    { key: 'all', label: '全部文章' },
    { key: 'published', label: '已发布' },
    { key: 'draft', label: '草稿' },
    { key: 'pending', label: '待审核' },
    { key: 'rejected', label: '已退回' }
  ],
  'my-comments': [
    { key: 'all', label: '全部评论' },
    { key: 'pending', label: '待审核' },
    { key: 'visible', label: '已展示' },
    { key: 'hidden', label: '已隐藏' }
  ],
  'site-info': [
    { key: 'basic', label: '基础信息' },
    { key: 'appearance', label: '品牌与关于' },
    { key: 'music', label: '站点音乐' },
    { key: 'footer', label: '页脚与联系' },
    { key: 'advanced', label: '高级与代码' }
  ],
  notifications: [
    { key: 'all', label: '全部通知' },
    { key: 'published', label: '已发布' },
    { key: 'draft', label: '未发布' }
  ],
  settings: [
    { key: 'smtp', label: 'SMTP' },
    { key: 'update', label: '站点更新' },
    { key: 'ai', label: 'AI 设置' },
    { key: 'registration', label: '注册设置' },
    { key: 'maintenance', label: '运行维护' }
  ]
}

const manageSectionKeys = {
  posts: 'manage-posts',
  comments: 'manage-comments',
  tags: 'manage-tags',
  announcements: 'manage-announcements',
  navigation: 'manage-navigation',
  users: 'manage-users',
  storage: 'manage-storage'
}

const currentSection = computed(() => {
  const path = route.path
  if (path.startsWith('/admin/profile')) return sectionByKey('profile')
  if (path.startsWith('/admin/roles')) return sectionByKey('roles')
  if (path.startsWith('/admin/my-posts')) return sectionByKey('my-posts')
  if (path.startsWith('/admin/my-comments')) return sectionByKey('my-comments')
  if (path.startsWith('/admin/manage/')) {
    const key = path.slice('/admin/manage/'.length).split('/')[0]
    return sectionByKey(manageSectionKeys[key] || 'manage-posts')
  }
  if (path === '/admin/manage' || path === '/admin/manage/') {
    return sectionByKey(manageSectionKeys[String(route.query.tab || '')] || 'manage-posts')
  }
  if (path.startsWith('/admin/posts') || path.startsWith('/admin/editor')) {
    return hasPermission('system:posts') ? sectionByKey('manage-posts') : sectionByKey('my-posts')
  }
  if (path.startsWith('/admin/users')) return sectionByKey('manage-users')
  if (path.startsWith('/admin/storage')) return sectionByKey('manage-storage')
  if (path.startsWith('/admin/site-info')) return sectionByKey('site-info')
  if (path.startsWith('/admin/notifications')) return sectionByKey('notifications')
  if (path.startsWith('/admin/settings')) return sectionByKey('settings')
  return visibleSections.value[0] || sections[0]
})

const isEditor = computed(() => route.name === 'admin-post-new' || route.name === 'admin-post-edit')
const sectionLabel = computed(() => currentSection.value?.label || '仪表盘')
const displayName = computed(() => state.user?.nickname || state.user?.username || '站长')
const roleLabel = computed(() => state.user?.roleName || (state.user?.role === 'ADMIN' ? '管理员' : '读者'))

const tabs = computed(() => {
  const list = sectionTabs[currentSection.value?.key] || []
  if (currentSection.value?.key !== 'dashboard') return list
  return list.filter((tab) => tab.key !== 'attention' || canManageContent.value)
})

watch(() => currentSection.value?.key, (key) => {
  const group = navGroups.value.find((item) => item.items.some((entry) => entry.key === key))
  if (group && !openGroups.value[group.key]) {
    openGroups.value = { ...openGroups.value, [group.key]: true }
    try {
      localStorage.setItem('admin_nav_groups', JSON.stringify(openGroups.value))
    } catch {
      /* ignore storage failures */
    }
  }
}, { immediate: true })
const activeTab = computed(() => {
  if (isEditor.value) return 'edit'
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
    router.push(hasPermission('system:posts') ? '/admin/manage/posts' : '/admin/my-posts')
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

        <nav class="admin-subnav" aria-label="后台页面筛选">
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
          v-for="section in primarySections"
          :key="section.key"
          :to="section.to"
          class="admin-nav-item is-primary"
          :class="{ active: currentSection?.key === section.key }"
          :title="collapsed ? section.label : ''"
          :aria-label="section.label"
          @click="closeMobile"
        >
          <span class="admin-nav-icon" aria-hidden="true"><AdminNavIcon :name="section.icon" /></span>
          <span v-if="!collapsed || mobileOpen" class="admin-nav-label">{{ section.label }}</span>
        </router-link>

        <div v-if="collapsed && !mobileOpen" class="admin-nav-rail-divider" aria-hidden="true"></div>

        <template v-if="!collapsed || mobileOpen">
          <div
            v-for="group in navGroups"
            :key="group.key"
            class="admin-nav-group"
            :class="{ expanded: openGroups[group.key], active: groupHasActive(group) }"
          >
            <button
              class="admin-nav-group-head"
              type="button"
              :aria-expanded="openGroups[group.key] ? 'true' : 'false'"
              :aria-controls="`admin-nav-children-${group.key}`"
              @click="toggleNavGroup(group.key)"
            >
              <span class="admin-nav-group-icon" aria-hidden="true"><AdminNavIcon :name="group.icon" /></span>
              <span class="admin-nav-group-title">{{ group.label }}</span>
              <small>{{ group.hint }}</small>
              <i class="admin-nav-group-chevron" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="m8 10 4 4 4-4" /></svg>
              </i>
            </button>
            <div
              :id="`admin-nav-children-${group.key}`"
              class="admin-nav-children"
              :class="{ open: openGroups[group.key] }"
              :inert="!openGroups[group.key]"
            >
              <div class="admin-nav-children-inner">
                <router-link
                  v-for="section in group.items"
                  :key="section.key"
                  :to="section.to"
                  class="admin-nav-item is-child"
                  :class="{ active: currentSection?.key === section.key }"
                  :aria-label="section.label"
                  @click="closeMobile"
                >
                  <span class="admin-nav-icon" aria-hidden="true"><AdminNavIcon :name="section.icon" /></span>
                  <span class="admin-nav-label">{{ section.label }}</span>
                </router-link>
              </div>
            </div>
          </div>
        </template>

        <template v-else>
          <router-link
            v-for="section in groupedSections"
            :key="section.key"
            :to="section.to"
            class="admin-nav-item is-child-rail"
            :class="{ active: currentSection?.key === section.key }"
            :title="section.label"
            :aria-label="section.label"
            @click="closeMobile"
          >
            <span class="admin-nav-icon" aria-hidden="true"><AdminNavIcon :name="section.icon" /></span>
          </router-link>
        </template>
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
