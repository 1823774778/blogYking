import { reactive } from 'vue'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem('blog_user') || 'null')
  } catch {
    return null
  }
}

const savedTheme = localStorage.getItem('blog_theme') || 'light'

export const state = reactive({
  token: localStorage.getItem('blog_token') || '',
  user: readUser(),
  site: {
    siteName: '墨记',
    siteDesc: '一个写字的地方：代码、生活与漫长的思考。',
    siteKeywords: '',
    announcement: '',
    footer: '用文字抵抗遗忘',
    icp: '',
    github: '',
    email: '',
    about: '',
    avatar: ''
  },
  theme: savedTheme,
  setupRequired: null,
  toasts: []
})

export function setAuth(token, user) {
  state.token = token || ''
  state.user = user || null
  if (token) {
    localStorage.setItem('blog_token', token)
  } else {
    localStorage.removeItem('blog_token')
  }
  if (user) {
    localStorage.setItem('blog_user', JSON.stringify(user))
  } else {
    localStorage.removeItem('blog_user')
  }
}

export function clearAuth() {
  state.token = ''
  state.user = null
  localStorage.removeItem('blog_token')
  localStorage.removeItem('blog_user')
}

export function applyTheme(theme) {
  state.theme = theme
  document.documentElement.setAttribute('data-theme', theme)
  localStorage.setItem('blog_theme', theme)
}

let toastTimer = null

export function toast(message, type = 'info') {
  const id = Date.now() + Math.random()
  state.toasts.push({ id, message, type })
  setTimeout(() => {
    state.toasts = state.toasts.filter((t) => t.id !== id)
  }, 2600)
}

export function isAdmin() {
  return state.user?.role === 'ADMIN'
}
