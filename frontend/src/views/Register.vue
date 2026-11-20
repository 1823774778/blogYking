<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { setAuth, state, toast } from '../state'

const route = useRoute()
const router = useRouter()

const form = ref({ email: '', password: '', confirm: '', emailCode: '', captchaAnswer: '' })
const loading = ref(false)
const error = ref('')
const codeLoading = ref(false)
const countdown = ref(0)
const captcha = ref({ id: '', image: '' })
const captchaLoading = ref(false)
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

async function loadCaptcha() {
  captchaLoading.value = true
  form.value.captchaAnswer = ''
  try {
    const res = await http.get('/auth/captcha')
    captcha.value = { id: res.data.id, image: res.data.image }
  } catch (e) {
    error.value = e.message
    captcha.value = { id: '', image: '' }
  } finally {
    captchaLoading.value = false
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
  if (!validEmail(f.email)) {
    error.value = '邮箱格式不正确'
    return
  }
  if (needEmailCode.value && !/^\d{6}$/.test(f.emailCode.trim())) {
    error.value = '请输入 6 位邮箱验证码'
    return
  }
  if (!f.captchaAnswer.trim()) {
    error.value = '请填写图片验证码的计算结果'
    return
  }
  if (!captcha.value.id) {
    error.value = '验证码已失效，请点击图片刷新'
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
      email: f.email.trim(),
      password: f.password,
      emailCode: needEmailCode.value ? f.emailCode.trim() : '',
      captchaId: captcha.value.id,
      captchaAnswer: f.captchaAnswer.trim()
    })
    setAuth(res.data.token, res.data.user)
    toast('注册成功，欢迎')
    const redirect = route.query.redirect ? String(route.query.redirect) : '/'
    router.push(redirect)
  } catch (e) {
    error.value = e.message
    await loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadMeta(), loadCaptcha()])
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="auth-page auth-page-register">
    <div class="auth-intro">
      <span class="hero-eyebrow">Create your space</span>
      <h2>用邮箱开一扇门，<br />把想法留在这里。</h2>
      <p>账号就是你的邮箱，不用再记第二个名字。注册后可以评论、收藏文章，也能在个人中心维护自己的资料。</p>
      <ul class="auth-points">
        <li><b>01</b><span>邮箱即用户名，登录更直接</span></li>
        <li><b>02</b><span>本地图形验证码，不依赖第三方</span></li>
        <li><b>03</b><span>邮箱验证开关由站长统一控制</span></li>
      </ul>
    </div>

    <div class="auth-card auth-card-register">
      <div v-if="!registrationOpen" class="auth-closed">
        <span class="hero-eyebrow">Registration closed</span>
        <h2>注册暂未开放</h2>
        <p>站长暂时关闭了新用户注册。你仍然可以登录已有账号，或稍后再来看看。</p>
        <router-link class="btn" to="/login">已有账号，去登录</router-link>
      </div>

      <template v-else>
        <div class="auth-card-head flex items-start justify-between gap-4">
          <div>
            <span class="hero-eyebrow">Join the journal</span>
            <h2>创建账号</h2>
            <p class="sub">{{ needEmailCode ? '邮箱验证 + 本地验证码，一分钟完成' : '邮箱即账号，填写密码即可注册' }}</p>
          </div>
          <span class="auth-step-pill">01 / 01</span>
        </div>

        <div v-if="error" class="form-error">{{ error }}</div>

        <form @submit.prevent="submit">
          <div class="field">
            <label for="email">邮箱 · 同时也是用户名</label>
            <input id="email" v-model="form.email" type="email" autocomplete="email" placeholder="you@example.com" />
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
          <p v-else class="auth-inline-note">本站未开启邮箱验证，本次注册不需要邮箱验证码。</p>
          <div class="field">
            <label for="password">密码</label>
            <input id="password" v-model="form.password" type="password" autocomplete="new-password" placeholder="至少 6 位" />
          </div>
          <div class="field">
            <label for="confirm">确认密码</label>
            <input id="confirm" v-model="form.confirm" type="password" autocomplete="new-password" placeholder="再输一次" />
          </div>
          <div class="field">
            <label for="captchaAnswer">安全验证 · 请计算图片中的结果</label>
            <div class="auth-captcha-row">
              <input id="captchaAnswer" v-model="form.captchaAnswer" type="text" inputmode="numeric" maxlength="3" autocomplete="off" placeholder="填写计算结果" />
              <button class="auth-captcha-image" type="button" :disabled="captchaLoading" title="点击刷新验证码" @click="loadCaptcha">
                <img v-if="captcha.image" :src="captcha.image" alt="图形验证码" />
                <span v-else>{{ captchaLoading ? '刷新中…' : '点击加载' }}</span>
              </button>
            </div>
            <p class="auth-field-hint">看不清？点击右侧图片换一道题。</p>
          </div>
          <button class="btn auth-submit" type="submit" :disabled="loading">
            {{ loading ? '注册中…' : '创建账号' }}
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
