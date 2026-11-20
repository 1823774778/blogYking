<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { setAuth, state, toast } from '../state'

const route = useRoute()
const router = useRouter()

const form = ref({ username: '', email: '', nickname: '', password: '', confirm: '', emailCode: '' })
const loading = ref(false)
const error = ref('')
const codeLoading = ref(false)
const countdown = ref(0)
let timer = null

const registrationOpen = computed(() => String(state.site.registrationEnabled ?? 'true').toLowerCase() !== 'false')
const needEmailCode = computed(() => String(state.site.registrationVerifyEmail ?? 'true').toLowerCase() === 'true')

function validEmail(value) {
  return /^[\w.+-]+@[\w-]+(\.[\w-]+)+$/.test(value.trim())
}

async function loadMeta() {
  try {
    const res = await http.get('/meta/site')
    state.site = { ...state.site, ...res.data }
  } catch (e) {
    /* 公开配置读取失败时按默认开放处理 */
  }
}

function startCountdown(seconds = 60) {
  countdown.value = seconds
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function sendCode() {
  error.value = ''
  if (!validEmail(form.value.email)) {
    error.value = '先填写正确的邮箱，再获取验证码'
    return
  }
  codeLoading.value = true
  try {
    await http.post('/auth/email-code', { email: form.value.email.trim() })
    startCountdown(60)
    toast('验证码已发送，请检查邮箱（包括垃圾箱）')
  } catch (e) {
    error.value = e.message
  } finally {
    codeLoading.value = false
  }
}

async function submit() {
  error.value = ''
  const f = form.value
  if (!registrationOpen.value) {
    error.value = '注册暂未开放'
    return
  }
  if (!/^[A-Za-z0-9_]{3,20}$/.test(f.username.trim())) {
    error.value = '用户名需为 3-20 位字母、数字或下划线'
    return
  }
  if (!validEmail(f.email)) {
    error.value = '邮箱格式不正确'
    return
  }
  if (needEmailCode.value && !/^\d{6}$/.test(f.emailCode.trim())) {
    error.value = '请输入 6 位邮箱验证码'
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
      password: f.password,
      emailCode: needEmailCode.value ? f.emailCode.trim() : ''
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

onMounted(loadMeta)
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="auth-page">
    <div class="auth-intro">
      <span class="hero-eyebrow">Join us</span>
      <h2>注册一个账号，<br />在这块地上留下脚印。</h2>
      <p>注册后可以评论、收藏喜欢的文章。邮箱会用于找回密码与注册验证，不会公开展示。</p>
    </div>

    <div class="auth-card">
      <div v-if="!registrationOpen" class="auth-closed">
        <span class="hero-eyebrow">Registration closed</span>
        <h2>注册暂未开放</h2>
        <p>站长暂时关闭了新用户注册。你仍然可以登录已有账号，或稍后再来看看。</p>
        <router-link class="btn" to="/login">已有账号，去登录</router-link>
      </div>

      <template v-else>
        <h2>注册</h2>
        <p class="sub">{{ needEmailCode ? '填写邮箱验证码，一分钟完成注册' : '一分钟，就够了' }}</p>

        <div v-if="error" class="form-error">{{ error }}</div>

        <form @submit.prevent="submit">
          <div class="field">
            <label for="email">邮箱</label>
            <input id="email" v-model="form.email" type="email" autocomplete="email" placeholder="用于找回密码与注册验证" />
          </div>
          <div v-if="needEmailCode" class="field">
            <label for="emailCode">邮箱验证码</label>
            <div class="auth-code-row">
              <input id="emailCode" v-model="form.emailCode" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码" />
              <button class="btn btn-ghost btn-sm" type="button" :disabled="codeLoading || countdown > 0" @click="sendCode">
                {{ codeLoading ? '发送中…' : countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
              </button>
            </div>
          </div>
          <div class="field">
            <label for="username">用户名</label>
            <input id="username" v-model="form.username" type="text" autocomplete="username" placeholder="3-20 位字母、数字或下划线" />
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
      </template>
    </div>
  </div>
</template>
