<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { setAuth, state, toast } from '../../state'
import { formatDate, initialOf } from '../../utils'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'profile'))
const form = reactive({ nickname: '', bio: '', avatar: '' })
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)
const savingPwd = ref(false)
const uploading = ref(false)
const avatarInput = ref(null)

function fill() {
  form.nickname = state.user?.nickname || ''
  form.bio = state.user?.bio || ''
  form.avatar = state.user?.avatar || ''
}

async function saveProfile() {
  if (!form.nickname.trim()) {
    toast('昵称不能为空', 'error')
    return
  }
  saving.value = true
  try {
    const res = await http.put('/auth/profile', {
      nickname: form.nickname.trim(),
      bio: form.bio.trim(),
      avatar: form.avatar
    })
    setAuth(state.token, res.data)
    toast('个人资料已保存')
    fill()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function savePassword() {
  if (!pwd.oldPassword) {
    toast('请输入原密码', 'error')
    return
  }
  if (pwd.newPassword.length < 6) {
    toast('新密码至少 6 位', 'error')
    return
  }
  if (pwd.newPassword !== pwd.confirm) {
    toast('两次输入的新密码不一致', 'error')
    return
  }
  savingPwd.value = true
  try {
    const res = await http.put('/auth/profile', {
      oldPassword: pwd.oldPassword,
      newPassword: pwd.newPassword
    })
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

function pickAvatar() {
  avatarInput.value?.click()
}

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
    <section class="adm-profile-banner">
      <span class="adm-profile-avatar">
        <img v-if="form.avatar" :src="form.avatar" alt="" />
        <template v-else>{{ initialOf(form.nickname || state.user?.username) }}</template>
      </span>
      <div class="adm-profile-identity">
        <strong>{{ form.nickname || state.user?.username }}</strong>
        <span>@{{ state.user?.username }} · {{ state.user?.email }}</span>
        <small>加入于 {{ formatDate(state.user?.createdAt) }}</small>
      </div>
      <div class="adm-profile-state">
        <span class="adm-status" :class="state.user?.enabled ? 'is-published' : 'is-rejected'">{{ state.user?.enabled ? '账号正常' : '账号已封禁' }}</span>
        <span v-if="state.user?.muted" class="adm-status is-pending">禁言至 {{ formatDate(state.user.mutedUntil) }}</span>
      </div>
    </section>

    <div v-if="activeTab === 'security'" class="adm-settings-grid">
      <section class="adm-card">
        <div class="adm-card-head">
          <div>
            <span class="adm-card-kicker">SECURITY</span>
            <h2>修改密码</h2>
          </div>
        </div>
        <div class="form-grid">
          <div class="field span-2">
            <label for="oldpwd">原密码</label>
            <input id="oldpwd" v-model="pwd.oldPassword" type="password" autocomplete="current-password" />
          </div>
          <div class="field">
            <label for="newpwd">新密码</label>
            <input id="newpwd" v-model="pwd.newPassword" type="password" autocomplete="new-password" placeholder="至少 6 位" />
          </div>
          <div class="field">
            <label for="confirmpwd">确认新密码</label>
            <input id="confirmpwd" v-model="pwd.confirm" type="password" autocomplete="new-password" />
          </div>
        </div>
        <button class="btn" type="button" :disabled="savingPwd" @click="savePassword">{{ savingPwd ? '更新中…' : '更新密码' }}</button>
      </section>

      <aside class="adm-side-stack">
        <section class="adm-card">
          <div class="adm-card-head">
            <div><span class="adm-card-kicker">ACCOUNT</span><h2>账号信息</h2></div>
          </div>
          <dl class="adm-facts">
            <div><dt>登录账号</dt><dd>{{ state.user?.username }}</dd></div>
            <div><dt>绑定邮箱</dt><dd>{{ state.user?.email }}</dd></div>
            <div><dt>账号角色</dt><dd>{{ state.user?.role === 'ADMIN' ? '管理员' : '读者' }}</dd></div>
            <div><dt>注册时间</dt><dd>{{ formatDate(state.user?.createdAt) }}</dd></div>
          </dl>
          <p class="adm-note">账号与邮箱由初始化或注册流程确定，后台暂不提供直接修改，避免破坏登录凭据。</p>
        </section>
      </aside>
    </div>

    <div v-else class="adm-settings-grid">
      <section class="adm-card">
        <div class="adm-card-head">
          <div>
            <span class="adm-card-kicker">IDENTITY</span>
            <h2>基本资料</h2>
          </div>
        </div>
        <div class="form-grid">
          <div class="field span-2">
            <label>头像</label>
            <div class="adm-avatar-editor">
              <span class="avatar lg">
                <img v-if="form.avatar" :src="form.avatar" alt="" />
                <template v-else>{{ initialOf(form.nickname || state.user?.username) }}</template>
              </span>
              <div>
                <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickAvatar">{{ uploading ? '上传中…' : '上传新头像' }}</button>
                <button v-if="form.avatar" class="btn-text" type="button" @click="form.avatar = ''">移除</button>
                <p class="meta">支持 jpg / png / webp，20MB 以内。</p>
              </div>
              <input ref="avatarInput" type="file" accept="image/*" hidden @change="uploadAvatar" />
            </div>
          </div>
          <div class="field">
            <label for="nickname">昵称</label>
            <input id="nickname" v-model="form.nickname" type="text" maxlength="32" />
          </div>
          <div class="field">
            <label>登录账号</label>
            <input :value="state.user?.username" type="text" disabled />
          </div>
          <div class="field span-2">
            <label for="bio">个人简介</label>
            <textarea id="bio" v-model="form.bio" maxlength="512" style="min-height: 100px" placeholder="介绍你自己，最多 512 个字符"></textarea>
          </div>
        </div>
        <button class="btn" type="button" :disabled="saving" @click="saveProfile">{{ saving ? '保存中…' : '保存个人资料' }}</button>
      </section>

      <aside class="adm-side-stack">
        <section class="adm-card adm-profile-preview">
          <div class="adm-card-head">
            <div><span class="adm-card-kicker">PREVIEW</span><h2>展示预览</h2></div>
          </div>
          <div class="adm-preview-avatar">
            <span class="avatar lg">
              <img v-if="form.avatar" :src="form.avatar" alt="" />
              <template v-else>{{ initialOf(form.nickname || state.user?.username) }}</template>
            </span>
            <strong>{{ form.nickname || state.user?.username }}</strong>
            <p>{{ form.bio || '这个人很安静，还没有写简介。' }}</p>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>
