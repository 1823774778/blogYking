import axios from 'axios'
import { state, clearAuth } from './state'
import router from './router'

const http = axios.create({
  baseURL: '/api',
  timeout: 20000,
  withCredentials: true
})

http.interceptors.request.use((config) => {
  if (state.token) {
    config.headers.Authorization = `Bearer ${state.token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || '网络异常，请稍后再试'
    if (status === 401) {
      const wasLoggedIn = Boolean(state.user)
      clearAuth()
      if (wasLoggedIn && !router.currentRoute.value.path.startsWith('/login')) {
        router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      }
    }
    const err = new Error(message)
    err.status = status
    return Promise.reject(err)
  }
)

export default http
