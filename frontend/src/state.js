import { reactive } from 'vue'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem('blog_user') || 'null')
  } catch {
    return null
  }
}

const savedThemeRaw = localStorage.getItem('blog_theme')
const savedTheme = ['light', 'dark', 'system'].includes(savedThemeRaw) ? savedThemeRaw : 'system'
const systemTheme = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null

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
    avatar: '',
    logo: '',
    musicEnabled: 'false',
    musicUrl: '',
    musicTitle: '',
    commentReview: 'false'
  },
  theme: savedTheme === 'system' ? (systemTheme?.matches ? 'dark' : 'light') : savedTheme,
  themePreference: savedTheme,
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

export function resolvedTheme(preference) {
  if (preference === 'system') return systemTheme?.matches ? 'dark' : 'light'
  return preference === 'dark' ? 'dark' : 'light'
}

export function applyTheme(preference) {
  const next = ['light', 'dark', 'system'].includes(preference) ? preference : 'system'
  state.themePreference = next
  state.theme = resolvedTheme(next)
  document.documentElement.setAttribute('data-theme', state.theme)
  document.documentElement.setAttribute('data-theme-preference', next)
  localStorage.setItem('blog_theme', next)
}

if (systemTheme) {
  const syncSystemTheme = () => {
    if (state.themePreference !== 'system') return
    state.theme = resolvedTheme('system')
    document.documentElement.setAttribute('data-theme', state.theme)
  }
  if (systemTheme.addEventListener) systemTheme.addEventListener('change', syncSystemTheme)
  else systemTheme.addListener?.(syncSystemTheme)
}

let toastTimer = null

export function toast(message, type = 'info') {
  if (state.toasts.some((item) => item.message === message && item.type === type)) {
    return
  }
  const id = Date.now() + Math.random()
  state.toasts.push({ id, message, type })
  if (state.toasts.length > 4) {
    state.toasts = state.toasts.slice(-4)
  }
  setTimeout(() => {
    state.toasts = state.toasts.filter((t) => t.id !== id)
  }, 2600)
}

export function isAdmin() {
  return state.user?.role === 'ADMIN'
}
