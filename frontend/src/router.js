import { createRouter, createWebHistory } from 'vue-router'
import { state } from './state'

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
  { path: '/admin', component: () => import('./views/Admin.vue'), meta: { title: '后台', auth: true, admin: true } },
  { path: '/admin/editor/:id?', component: () => import('./views/AdminEditor.vue'), meta: { title: '写文章', auth: true, admin: true } },
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
  if (to.meta.auth && !state.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.admin && state.user?.role !== 'ADMIN') {
    return { path: '/' }
  }
})

router.afterEach((to) => {
  const name = state.site?.siteName || '墨记'
  document.title = to.meta.title ? `${to.meta.title} · ${name}` : name
})

export default router
