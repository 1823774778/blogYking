<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { setAuth, toast } from '../state'

const route = useRoute()
const router = useRouter()

const account = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

const registerLink = computed(() => {
  const redirect = route.query.redirect
  return redirect ? `/register?redirect=${encodeURIComponent(String(redirect))}` : '/register'
})

async function submit() {
  error.value = ''
  if (!account.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  try {
    const res = await http.post('/auth/login', {
      account: account.value.trim(),
      password: password.value
    })
    setAuth(res.data.token, res.data.user)
    toast('欢迎回来')
    const redirect = route.query.redirect ? String(route.query.redirect) : '/'
    router.push(redirect)
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
      <span class="hero-eyebrow">Welcome back</span>
      <h2>登录，<br />继续读，或者接着写。</h2>
      <p>登录后可以评论、点赞、收藏文章，也能进入个人中心管理自己的资料。</p>
    </div>

    <div class="auth-card">
      <h2>登录</h2>
      <p class="sub">用户名或邮箱都可以</p>

      <div v-if="error" class="form-error">{{ error }}</div>

      <form @submit.prevent="submit">
        <div class="field">
          <label for="account">账号</label>
          <input id="account" v-model="account" type="text" autocomplete="username" placeholder="用户名 / 邮箱" />
        </div>
        <div class="field">
          <label for="password">密码</label>
          <input id="password" v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" />
        </div>
        <button class="btn" type="submit" style="width: 100%" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>

      <div class="form-tip">
        <router-link to="/forgot-password">忘记密码？</router-link>
        <span>通过邮箱重置</span>
      </div>

      <div class="auth-foot">
        <span>还没有账号？</span>
        <router-link :to="registerLink">注册一个</router-link>
      </div>
    </div>
  </div>
</template>
