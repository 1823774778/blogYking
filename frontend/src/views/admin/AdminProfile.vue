<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { setAuth, state, toast } from '../../state'
import { formatDate, initialOf } from '../../utils'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'profile'))
const form = reactive({
  nickname: '', email: '', bio: '', avatar: '', phone: '', gender: '', birthday: ''
})
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)
const savingPwd = ref(false)
const uploading = ref(false)
const avatarInput = ref(null)

function fill() {
  form.nickname = state.user?.nickname || ''
  form.email = state.user?.email || ''
  form.bio = state.user?.bio || ''
  form.avatar = state.user?.avatar || ''
  form.phone = state.user?.phone || ''
  form.gender = state.user?.gender || ''
  form.birthday = state.user?.birthday ? String(state.user.birthday).slice(0, 10) : ''
}

async function saveProfile() {
  if (!form.nickname.trim()) { toast('昵称不能为空', 'error'); return }
  if (!form.email.trim()) { toast('邮箱不能为空', 'error'); return }
  const emailChanged = form.email.trim().toLowerCase() !== (state.user?.email || '')
  if (emailChanged && !pwd.oldPassword) { toast('修改登录邮箱需要先在「账号安全」输入当前密码', 'error'); return }
  saving.value = true
  try {
    const res = await http.put('/auth/profile', {
      nickname: form.nickname.trim(),
      email: form.email.trim().toLowerCase(),
      bio: form.bio.trim(),
      avatar: form.avatar,
      phone: form.phone.trim(),
      gender: form.gender,
      birthday: form.birthday || null,
      oldPassword: emailChanged ? pwd.oldPassword : null
    })
    setAuth(state.token, res.data)
    pwd.oldPassword = ''
    toast('个人资料已保存')
    fill()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function savePassword() {
  if (!pwd.oldPassword) { toast('请输入原密码', 'error'); return }
  if (pwd.newPassword.length < 6) { toast('新密码至少 6 位', 'error'); return }
  if (pwd.newPassword !== pwd.confirm) { toast('两次输入的新密码不一致', 'error'); return }
  savingPwd.value = true
  try {
    const res = await http.put('/auth/profile', { oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    setAuth(state.token, res.data)
    pwd.oldPassword = ''
    pwd.newPassword = ''
    pwd.confirm = ''
    toast('密码已更新')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingPwd.value = false
  }
}

function pickAvatar() { avatarInput.value?.click() }

async function uploadAvatar(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  uploading.value = true
  try {
    const res = await http.post('/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    form.avatar = res.data.url
    toast('头像已上传，保存后生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

onMounted(fill)
</script>

<template>
  <div class="adm-page">
    <section class="profile-hero">
      <div class="profile-avatar-wrap" @click="pickAvatar">
        <span class="profile-avatar">
          <img v-if="form.avatar" :src="form.avatar" alt="" />
          <template v-else>{{ initialOf(form.nickname || state.user?.username) }}</template>
        </span>
        <span class="profile-avatar-edit">{{ uploading ? '上传中' : '更换头像' }}</span>
      </div>
      <div class="profile-identity">
        <strong>{{ form.nickname || state.user?.username }}</strong>
        <span>@{{ state.user?.username }} · {{ state.user?.email }}</span>
        <small>{{ state.user?.roleName || (state.user?.role === 'ADMIN' ? '管理员' : '读者') }} · 加入于 {{ formatDate(state.user?.createdAt) }}</small>
      </div>
      <div class="profile-state">
        <span class="adm-status" :class="state.user?.enabled ? 'is-published' : 'is-rejected'">{{ state.user?.enabled ? '账号正常' : '账号已封禁' }}</span>
        <router-link class="btn btn-ghost btn-sm" :to="`/profile`">查看公开主页</router-link>
      </div>
    </section>

    <div class="profile-tabs">
      <button type="button" :class="{ on: activeTab === 'profile' }" @click="$router.replace({ path: '/admin/profile', query: { tab: 'profile' } })">基本资料</button>
      <button type="button" :class="{ on: activeTab === 'security' }" @click="$router.replace({ path: '/admin/profile', query: { tab: 'security' } })">账号安全</button>
    </div>

    <template v-if="activeTab === 'security'">
      <section class="adm-card profile-panel">
        <div class="adm-card-head"><div><span class="adm-card-kicker">SECURITY</span><h2>修改密码</h2><p>修改后现有登录状态保持有效，下次登录使用新密码。</p></div></div>
        <div class="profile-form-grid">
          <div class="field span-2"><label>原密码</label><input v-model="pwd.oldPassword" type="password" autocomplete="current-password" /></div>
          <div class="field"><label>新密码</label><input v-model="pwd.newPassword" type="password" autocomplete="new-password" placeholder="至少 6 位" /></div>
          <div class="field"><label>确认新密码</label><input v-model="pwd.confirm" type="password" autocomplete="new-password" /></div>
        </div>
        <div class="profile-card-actions"><button class="btn btn-sm" type="button" :disabled="savingPwd" @click="savePassword">{{ savingPwd ? '保存中…' : '更新密码' }}</button></div>
      </section>
      <section class="adm-card profile-panel profile-note">
        <div class="adm-card-head"><div><span class="adm-card-kicker">ACCOUNT</span><h2>账号信息</h2></div></div>
        <div class="profile-facts">
          <div><span>登录账号</span><strong>{{ state.user?.username }}</strong></div>
          <div><span>当前角色</span><strong>{{ state.user?.roleName || state.user?.role }}</strong></div>
          <div><span>注册时间</span><strong>{{ formatDate(state.user?.createdAt) }}</strong></div>
        </div>
        <p class="meta">本项目不提供 API Key 管理；第三方接入请在对应服务商处单独管理凭证。</p>
      </section>
    </template>

    <template v-else>
      <section class="adm-card profile-panel">
        <div class="adm-card-head"><div><span class="adm-card-kicker">PROFILE</span><h2>基本资料</h2><p>这些信息会展示在文章署名与公开个人主页。</p></div></div>
        <div class="profile-form-grid">
          <div class="field"><label>昵称</label><input v-model="form.nickname" maxlength="32" placeholder="显示名称" /></div>
          <div class="field"><label>登录邮箱</label><input v-model="form.email" type="email" maxlength="128" /></div>
          <div class="field"><label>手机号</label><input v-model="form.phone" maxlength="32" placeholder="可选，仅后台可见" /></div>
          <div class="field"><label>性别</label><select v-model="form.gender"><option value="">未设置</option><option value="male">男</option><option value="female">女</option><option value="other">其他</option></select></div>
          <div class="field"><label>生日</label><input v-model="form.birthday" type="date" /></div>
          <div class="field"><label>头像地址</label><input v-model="form.avatar" placeholder="上传后自动填充" /></div>
          <div class="field span-2"><label>个人简介</label><textarea v-model="form.bio" rows="5" maxlength="512" placeholder="介绍一下自己"></textarea></div>
        </div>
        <div class="profile-hint">修改登录邮箱时需要输入当前密码，可在「账号安全」中填写后再保存。</div>
        <div class="profile-card-actions">
          <button class="btn btn-ghost btn-sm" type="button" @click="fill">还原</button>
          <button class="btn btn-sm" type="button" :disabled="saving" @click="saveProfile">{{ saving ? '保存中…' : '保存资料' }}</button>
        </div>
      </section>
    </template>

    <input ref="avatarInput" type="file" accept="image/*" hidden @change="uploadAvatar" />
  </div>
</template>

<style scoped>
.profile-hero { display: flex; align-items: center; gap: 18px; padding: 24px; border: 1px solid var(--line); border-radius: 14px; background: linear-gradient(135deg, var(--accent-soft), var(--paper) 60%); }
.profile-avatar-wrap { position: relative; flex: 0 0 auto; width: 82px; height: 82px; cursor: pointer; }
.profile-avatar { display: grid; place-items: center; width: 82px; height: 82px; overflow: hidden; border-radius: 50%; background: var(--accent); color: #fff; font-size: 28px; font-weight: 800; border: 4px solid var(--paper); box-shadow: 0 8px 24px rgba(15, 40, 80, .14); }
.profile-avatar img { width: 100%; height: 100%; object-fit: cover; }
.profile-avatar-edit { position: absolute; inset: auto 0 0; padding: 2px 0; border-radius: 0 0 999px 999px; background: rgba(5, 15, 30, .68); color: #fff; font-size: 9px; text-align: center; opacity: 0; transition: opacity .16s ease; }
.profile-avatar-wrap:hover .profile-avatar-edit { opacity: 1; }
.profile-identity { flex: 1; display: grid; gap: 4px; }
.profile-identity strong { font-size: 22px; color: var(--ink); }
.profile-identity span { color: var(--muted); font-size: 12px; }
.profile-identity small { color: var(--muted); font-size: 11px; }
.profile-state { display: flex; align-items: center; gap: 9px; }
.profile-tabs { display: flex; gap: 4px; margin: 18px 0 0; border-bottom: 1px solid var(--line); }
.profile-tabs button { padding: 9px 14px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--muted); font-size: 13px; cursor: pointer; }
.profile-tabs button.on { border-bottom-color: var(--accent); color: var(--ink); font-weight: 700; }
.profile-panel { margin-top: 16px; max-width: 900px; }
.profile-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; padding: 18px; }
.profile-form-grid .span-2 { grid-column: span 2; }
.profile-card-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 0 18px 18px; }
.profile-hint { margin: 0 18px 14px; padding: 9px 11px; border-radius: 8px; background: var(--accent-soft); color: var(--ink-soft); font-size: 12px; }
.profile-note .profile-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; padding: 18px; }
.profile-facts div { display: grid; gap: 4px; }
.profile-facts span { color: var(--muted); font-size: 11px; }
.profile-facts strong { color: var(--ink); font-size: 13px; }
.profile-note .meta { padding: 0 18px 18px; color: var(--muted); font-size: 12px; }
@media (max-width: 720px) { .profile-hero { align-items: flex-start; flex-wrap: wrap; } .profile-state { width: 100%; } .profile-form-grid, .profile-note .profile-facts { grid-template-columns: 1fr; } .profile-form-grid .span-2 { grid-column: auto; } }
</style>
