<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api'
import { state } from '../state'

const email = ref('')
const loading = ref(false)
const error = ref('')
const result = ref(null)

const emailFlowEnabled = computed(() =>
  String(state.site.registrationVerifyEmail ?? 'true').toLowerCase() === 'true'
)
const contactLink = computed(() => state.site.email ? `mailto:${state.site.email}` : '')

async function loadMeta() {
  try {
    const res = await http.get('/meta/site')
    state.site = { ...state.site, ...res.data }
  } catch (e) {
    /* 配置读取失败时按默认开启处理 */
  }
}

async function submit() {
  error.value = ''
  result.value = null
  if (!emailFlowEnabled.value) {
    error.value = '本站未开启邮箱验证，找回密码功能不可用。请携带账号凭证联系管理员更新或找回密码。'
    return
  }
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

onMounted(loadMeta)
</script>

<template>
  <div class="auth-page">
    <div class="auth-intro">
      <span class="hero-eyebrow">Account recovery</span>
      <h2>忘了密码，<br />先确认这扇门还开着。</h2>
      <p>邮箱找回依赖站长开启的邮箱验证。若未开启，请直接携带能证明账号归属的凭证联系管理员。</p>
      <ul class="auth-points">
        <li><b>01</b><span>重置链接 30 分钟内有效</span></li>
        <li><b>02</b><span>不会暴露邮箱是否已注册</span></li>
        <li><b>03</b><span>未开启邮箱验证时自动停用</span></li>
      </ul>
    </div>

    <div class="auth-card auth-card-register">
      <div class="auth-card-head flex items-start justify-between gap-4">
        <div>
          <span class="hero-eyebrow">Password reset</span>
          <h2>找回密码</h2>
          <p class="sub">通过注册邮箱验证账号归属</p>
        </div>
        <span class="auth-step-pill">{{ emailFlowEnabled ? 'Mail' : 'Off' }}</span>
      </div>

      <div v-if="!emailFlowEnabled" class="auth-disabled-panel">
        <div class="auth-disabled-icon" aria-hidden="true">!</div>
        <h3>邮箱找回暂不可用</h3>
        <p>本站当前未开启邮箱验证，因此找回密码功能已停用。请携带账号凭证联系管理员更新或找回密码。</p>
        <a v-if="contactLink" class="btn btn-sm" :href="contactLink">联系管理员</a>
        <router-link v-else class="btn btn-sm" to="/about">查看站点联系方式</router-link>
      </div>

      <template v-else>
        <div v-if="error" class="form-error">{{ error }}</div>

        <form v-if="!result" @submit.prevent="submit">
          <div class="field">
            <label for="email">注册邮箱</label>
            <input id="email" v-model="email" type="email" autocomplete="email" placeholder="you@example.com" />
          </div>
          <button class="btn auth-submit" type="submit" :disabled="loading">
            {{ loading ? '发送中…' : '发送重置链接' }}
          </button>
        </form>

        <div v-else>
          <div class="auth-success-panel">
            <div class="auth-success-icon" aria-hidden="true">✓</div>
            <h3>邮件已发出</h3>
            <p>{{ result.message }}</p>
            <div v-if="result.devLink" class="dev-link">
              当前 SMTP 未启用，开发模式可直接使用此链接：<br />
              <router-link :to="result.devLink.replace(/^https?:\/\/[^/]+/, '')">{{ result.devLink }}</router-link>
            </div>
          </div>
          <div class="form-tip">
            <router-link to="/login">返回登录</router-link>
            <button class="btn-text" type="button" @click="result = null">换个邮箱</button>
          </div>
        </div>
      </template>

      <div class="auth-foot">
        <span>想起来了？</span>
        <router-link to="/login">去登录</router-link>
      </div>
    </div>
  </div>
</template>
