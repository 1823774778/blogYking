<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { state, applyTheme, clearAuth, toast } from '../state'
import { initialOf } from '../utils'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const menuRef = ref(null)
const logoFailed = ref(false)
const showSiteLogo = computed(() => Boolean(state.site.logo) && !logoFailed.value)

watch(() => state.site.logo, () => {
  logoFailed.value = false
})

function onLogoError() {
  logoFailed.value = true
}

function toggleTheme() {
  applyTheme(state.theme === 'dark' ? 'light' : 'dark')
}

function logout() {
  clearAuth()
  menuOpen.value = false
  toast('已退出登录')
  router.push('/')
}

function onDocumentClick(e) {
  if (menuRef.value && !menuRef.value.contains(e.target)) {
    menuOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocumentClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick))
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

      <nav class="nav-links">
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
      </nav>

      <div class="nav-right">
        <button
          class="icon-btn"
          type="button"
          :title="state.theme === 'dark' ? '切换到浅色' : '切换到深色'"
          @click="toggleTheme"
        >
          <svg v-if="state.theme === 'dark'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z" />
          </svg>
        </button>

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
