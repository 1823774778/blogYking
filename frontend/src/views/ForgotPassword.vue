<script setup>
import { ref } from 'vue'
import http from '../api'

const email = ref('')
const loading = ref(false)
const error = ref('')
const result = ref(null)

async function submit() {
  error.value = ''
  result.value = null
  if (!email.value.trim()) {
    error.value = '请输入注册时使用的邮箱'
    return
  }
  loading.value = true
  try {
    const res = await http.post('/auth/forgot-password', { email: email.value.trim() })
    result.value = res.data
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
      <span class="hero-eyebrow">Password reset</span>
      <h2>忘了密码，<br />不是大事。</h2>
      <p>输入注册邮箱，我们会发送一封带有重置链接的邮件，30 分钟内有效。</p>
    </div>

    <div class="auth-card">
      <h2>找回密码</h2>
      <p class="sub">重置链接会发送到你的邮箱</p>

      <div v-if="error" class="form-error">{{ error }}</div>

      <form v-if="!result" @submit.prevent="submit">
        <div class="field">
          <label for="email">注册邮箱</label>
          <input id="email" v-model="email" type="email" autocomplete="email" placeholder="you@example.com" />
        </div>
        <button class="btn" type="submit" style="width: 100%" :disabled="loading">
          {{ loading ? '发送中…' : '发送重置链接' }}
        </button>
      </form>

      <div v-else>
        <div class="form-error" style="background: var(--bg-soft); border-left-color: var(--good); color: var(--ink-soft)">
          {{ result.message }}
        </div>
        <div v-if="result.devLink" class="dev-link">
          当前 SMTP 未启用，开发模式可直接使用此链接：<br />
          <router-link :to="result.devLink.replace(/^https?:\/\/[^/]+/, '')">{{ result.devLink }}</router-link>
        </div>
        <div class="form-tip">
          <router-link to="/login">返回登录</router-link>
          <button class="btn-text" type="button" @click="result = null">换个邮箱</button>
        </div>
      </div>

      <div class="auth-foot">
        <span>想起来了？</span>
        <router-link to="/login">去登录</router-link>
      </div>
    </div>
  </div>
</template>
