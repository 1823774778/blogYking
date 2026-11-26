import { createRouter, createWebHistory } from 'vue-router'
import { hasAnyPermission, isAdmin, state } from './state'

const MANAGE_TABS = ['posts', 'comments', 'tags', 'announcements', 'navigation', 'users', 'storage']

function manageRedirect(to) {
  const tab = typeof to.query.tab === 'string' && MANAGE_TABS.includes(to.query.tab) ? to.query.tab : 'posts'
  const view = typeof to.query.view === 'string' ? to.query.view : ''
  return {
    path: `/admin/manage/${tab}`,
    query: view ? { tab: view } : {}
  }
}

const routes = [
  { path: '/', component: () => import('./views/Home.vue'), meta: { title: '首页' } },
  { path: '/setup', component: () => import('./views/Setup.vue'), meta: { title: '初始化' } },
  { path: '/about', component: () => import('./views/About.vue'), meta: { title: '关于' } },
  { path: '/post/:id', component: () => import('./views/PostDetail.vue'), meta: { title: '文章' } },
  { path: '/login', component: () => import('./views/Login.vue'), meta: { title: '登录' } },
  { path: '/register', component: () => import('./views/Register.vue'), meta: { title: '注册' } },
  { path: '/forgot-password', component: () => import('./views/ForgotPassword.vue'), meta: { title: '找回密码' } },
  { path: '/reset-password', component: () => import('./views/ResetPassword.vue'), meta: { title: '重置密码' } },
  { path: '/profile', component: () => import('./views/Profile.vue'), meta: { title: '个人中心', auth: true } },
  {
    path: '/admin',
    component: () => import('./layouts/AdminLayout.vue'),
    meta: { title: '后台', auth: true, admin: true },
    children: [
      { path: '', name: 'admin-dashboard', component: () => import('./views/Admin.vue'), meta: { title: '仪表盘' } },
      { path: 'profile', name: 'admin-profile', component: () => import('./views/admin/AdminProfile.vue'), meta: { title: '个人中心', permissions: ['system:profile'] } },
      {
        path: 'manage',
        redirect: manageRedirect
      },
      {
        path: 'manage/posts',
        name: 'admin-manage-posts',
        component: () => import('./views/admin/AdminPosts.vue'),
        meta: { title: '文章管理', permissions: ['system:posts'] }
      },
      {
        path: 'manage/comments',
        name: 'admin-manage-comments',
        component: () => import('./views/admin/AdminComments.vue'),
        meta: { title: '评论管理', permissions: ['system:comments'] }
      },
      {
        path: 'manage/tags',
        name: 'admin-manage-tags',
        component: () => import('./views/admin/AdminTags.vue'),
        meta: { title: '标签管理', permissions: ['system:tags'] }
      },
      {
        path: 'manage/announcements',
        name: 'admin-manage-announcements',
        component: () => import('./views/admin/AdminAnnouncements.vue'),
        meta: { title: '公告管理', permissions: ['system:announcements'] }
      },
      {
        path: 'manage/navigation',
        name: 'admin-manage-navigation',
        component: () => import('./views/admin/AdminNavigation.vue'),
        meta: { title: '导航管理', permissions: ['system:navigation'] }
      },
      {
        path: 'manage/users',
        name: 'admin-manage-users',
        component: () => import('./views/admin/AdminUsers.vue'),
        meta: { title: '用户管理', permissions: ['system:users'] }
      },
      {
        path: 'manage/storage',
        name: 'admin-manage-storage',
        component: () => import('./views/admin/AdminStorage.vue'),
        meta: { title: '存储管理', permissions: ['system:storage'] }
      },
      {
        path: 'my-posts',
        name: 'admin-my-posts',
        component: () => import('./views/admin/AdminPosts.vue'),
        props: { mine: true },
        meta: { title: '我的文章', permissions: ['system:my-posts', 'system:posts'] }
      },
      {
        path: 'my-comments',
        name: 'admin-my-comments',
        component: () => import('./views/admin/AdminComments.vue'),
        props: { mine: true },
        meta: { title: '我的评论', permissions: ['system:my-comments', 'system:comments'] }
      },
      {
        path: 'users',
        redirect: (to) => ({
          path: '/admin/manage/users',
          query: typeof to.query.tab === 'string' ? { tab: to.query.tab } : {}
        })
      },
      { path: 'roles', name: 'admin-roles', component: () => import('./views/admin/AdminRoles.vue'), meta: { title: '角色权限', permissions: ['system:roles'] } },
      { path: 'site-info', name: 'admin-site-info', component: () => import('./views/admin/AdminSiteInfo.vue'), meta: { title: '站点信息', permissions: ['system:site'] } },
      { path: 'notifications', name: 'admin-notifications', component: () => import('./views/admin/AdminNotifications.vue'), meta: { title: '通知设置', permissions: ['system:notifications'] } },
      {
        path: 'storage',
        redirect: { path: '/admin/manage/storage' }
      },
      { path: 'settings', name: 'admin-settings', component: () => import('./views/admin/AdminSettings.vue'), meta: { title: '站点设置', permissions: ['system:settings'] } },
      {
        path: 'posts',
        redirect: (to) => ({
          path: '/admin/manage/posts',
          query: typeof to.query.tab === 'string' ? { tab: to.query.tab } : {}
        })
      },
      {
        path: 'comments',
        redirect: (to) => ({
          path: '/admin/manage/comments',
          query: typeof to.query.tab === 'string' ? { tab: to.query.tab } : {}
        })
      },
      { path: 'tags', redirect: { path: '/admin/manage/tags' } },
      { path: 'announcements', redirect: { path: '/admin/manage/announcements' } },
      { path: 'navigation', redirect: { path: '/admin/manage/navigation' } }
    ]
  },
  { path: '/admin/posts/new', name: 'admin-post-new', component: () => import('./views/AdminEditor.vue'), meta: { title: '新建文章', auth: true, admin: true, permissions: ['system:my-posts', 'system:posts'] } },
  { path: '/admin/posts/:id/edit', name: 'admin-post-edit', component: () => import('./views/AdminEditor.vue'), meta: { title: '编辑文章', auth: true, admin: true, permissions: ['system:my-posts', 'system:posts'] } },
  { path: '/admin/editor/:id?', redirect: (to) => (to.params.id ? `/admin/posts/${to.params.id}/edit` : '/admin/posts/new') },
  { path: '/:pathMatch(.*)*', component: () => import('./views/NotFound.vue'), meta: { title: '找不到页面' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 }
  }
})

router.beforeEach((to) => {
  if (state.setupRequired === true && to.path !== '/setup') {
    return { path: '/setup' }
  }
  const authRequired = to.matched.some((record) => record.meta.auth)
  const adminRequired = to.matched.some((record) => record.meta.admin)
  if (authRequired && !state.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (adminRequired && !isAdmin()) {
    return { path: '/' }
  }
  const requiredPermissions = to.matched
    .map((record) => record.meta.permissions)
    .filter((permissions) => Array.isArray(permissions) && permissions.length)
  if (adminRequired && requiredPermissions.length) {
    const allowed = requiredPermissions.every((permissions) => hasAnyPermission(permissions))
    if (!allowed) {
      return { path: '/admin' }
    }
  }
})

router.afterEach((to) => {
  const name = state.site?.siteName || '墨记'
  document.title = to.meta.title ? `${to.meta.title} · ${name}` : name
})

export default router
