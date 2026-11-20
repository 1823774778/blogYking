<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { setAuth, toast } from '../state'

const route = useRoute()
const router = useRouter()

const form = ref({ username: '', email: '', nickname: '', password: '', confirm: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  const f = form.value
  if (!/^[A-Za-z0-9_]{3,20}$/.test(f.username.trim())) {
    error.value = '用户名需为 3-20 位字母、数字或下划线'
    return
  }
  if (!/^[\w.+-]+@[\w-]+(\.[\w-]+)+$/.test(f.email.trim())) {
    error.value = '邮箱格式不正确'
    return
  }
  if (f.password.length < 6) {
    error.value = '密码至少 6 位'
    return
  }
  if (f.password !== f.confirm) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    const res = await http.post('/auth/register', {
      username: f.username.trim(),
      email: f.email.trim(),
      nickname: f.nickname.trim(),
      password: f.password
    })
    setAuth(res.data.token, res.data.user)
    toast('注册成功，欢迎')
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
      <span class="hero-eyebrow">Join us</span>
      <h2>注册一个账号，<br />在这块地上留下脚印。</h2>
      <p>注册后可以评论、收藏喜欢的文章。邮箱会用于找回密码，不会公开展示。</p>
    </div>

    <div class="auth-card">
      <h2>注册</h2>
      <p class="sub">一分钟，就够了</p>

      <div v-if="error" class="form-error">{{ error }}</div>

      <form @submit.prevent="submit">
        <div class="field">
          <label for="username">用户名</label>
          <input id="username" v-model="form.username" type="text" autocomplete="username" placeholder="3-20 位字母、数字或下划线" />
        </div>
        <div class="field">
          <label for="email">邮箱</label>
          <input id="email" v-model="form.email" type="email" autocomplete="email" placeholder="用于找回密码" />
        </div>
        <div class="field">
          <label for="nickname">昵称（可选）</label>
          <input id="nickname" v-model="form.nickname" type="text" placeholder="显示在评论里的名字" />
        </div>
        <div class="field">
          <label for="password">密码</label>
          <input id="password" v-model="form.password" type="password" autocomplete="new-password" placeholder="至少 6 位" />
        </div>
        <div class="field">
          <label for="confirm">确认密码</label>
          <input id="confirm" v-model="form.confirm" type="password" autocomplete="new-password" placeholder="再输一次" />
        </div>
        <button class="btn" type="submit" style="width: 100%" :disabled="loading">
          {{ loading ? '注册中…' : '注册' }}
        </button>
      </form>

      <div class="auth-foot">
        <span>已经有账号？</span>
        <router-link to="/login">直接登录</router-link>
      </div>
    </div>
  </div>
</template>
