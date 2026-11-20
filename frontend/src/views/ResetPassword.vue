<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { toast } from '../state'

const route = useRoute()
const router = useRouter()

const token = computed(() => (route.query.token ? String(route.query.token) : ''))
const password = ref('')
const confirm = ref('')
const loading = ref(false)
const error = ref('')
const done = ref(false)

async function submit() {
  error.value = ''
  if (!token.value) {
    error.value = '链接缺少 token，请重新申请找回密码'
    return
  }
  if (password.value.length < 6) {
    error.value = '密码至少 6 位'
    return
  }
  if (password.value !== confirm.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    await http.post('/auth/reset-password', { token: token.value, password: password.value })
    done.value = true
    toast('密码已重置')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-intro">
      <span class="hero-eyebrow">New password</span>
      <h2>设置一个新密码，<br />然后继续。</h2>
      <p>重置链接只能使用一次，过期后需要重新申请。</p>
    </div>

    <div class="auth-card">
      <h2>重置密码</h2>
      <p class="sub">请设置 6 位以上的新密码</p>

      <div v-if="error" class="form-error">{{ error }}</div>

      <div v-if="done">
        <div class="form-error" style="background: var(--bg-soft); border-left-color: var(--good); color: var(--ink-soft)">
          密码已重置，请使用新密码登录。
        </div>
        <router-link class="btn" to="/login" style="width: 100%">去登录</router-link>
      </div>

      <form v-else @submit.prevent="submit">
        <div class="field">
          <label for="password">新密码</label>
          <input id="password" v-model="password" type="password" autocomplete="new-password" placeholder="至少 6 位" />
        </div>
        <div class="field">
          <label for="confirm">确认新密码</label>
          <input id="confirm" v-model="confirm" type="password" autocomplete="new-password" placeholder="再输一次" />
        </div>
        <button class="btn" type="submit" style="width: 100%" :disabled="loading || !token">
          {{ loading ? '提交中…' : '重置密码' }}
        </button>
      </form>

      <div class="auth-foot">
        <span>链接失效了？</span>
        <router-link to="/forgot-password">重新申请</router-link>
      </div>
    </div>
  </div>
</template>
