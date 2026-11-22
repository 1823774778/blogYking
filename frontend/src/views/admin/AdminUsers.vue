<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { state, toast } from '../../state'
import { formatDate, formatDateTime, initialOf } from '../../utils'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'all'))
const keyword = ref('')
const users = reactive({ records: [], total: 0, page: 1, size: 15 })
const stats = ref({})
const roles = ref([])
const loading = ref(false)
const modalOpen = ref(false)
const modalMode = ref('create')
const editingId = ref(null)
const saving = ref(false)
const uploadingAvatar = ref(false)
const avatarInput = ref(null)
const muteTarget = ref(null)
const muteSaving = ref(false)
const muteForm = reactive({ duration: 7, reason: '' })

const form = reactive({
  email: '', nickname: '', password: '', role: 'USER', enabled: true, avatar: '',
  phone: '', gender: '', birthday: ''
})

const tabMeta = computed(() => ({
  all: { label: '全部用户', desc: '所有注册账号' },
  admins: { label: '管理员', desc: '拥有后台权限的账号' },
  banned: { label: '已封禁', desc: '无法登录的账号' },
  muted: { label: '已禁言', desc: '可登录但不能评论' }
}[activeTab.value] || { label: '全部用户', desc: '所有注册账号' }))

const roleOptions = computed(() => roles.value.filter((r) => r.enabled))

function params(page = users.page) {
  const p = { page, size: users.size }
  if (keyword.value.trim()) p.keyword = keyword.value.trim()
  if (activeTab.value === 'admins') p.role = 'ADMIN'
  if (activeTab.value === 'banned') p.status = 'disabled'
  if (activeTab.value === 'muted') p.muted = true
  return p
}

async function load(page = 1) {
  loading.value = true
  try {
    const res = await http.get('/admin/user-manage', { params: params(page) })
    users.records = res.data.records || []
    users.total = res.data.total || 0
    users.page = res.data.page || page
    stats.value = res.data.stats || {}
    roles.value = res.data.roles || []
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

function displayName(user) { return user.nickname || user.username }
function applySearch() { load(1) }
function muteLabel(user) {
  if (!user.muted) return ''
  if (!user.mutedUntil || String(user.mutedUntil).startsWith('9999')) return '永久禁言'
  return `禁言至 ${formatDateTime(user.mutedUntil)}`
}

function openCreate() {
  modalMode.value = 'create'
  editingId.value = null
  Object.assign(form, { email: '', nickname: '', password: '', role: 'USER', enabled: true, avatar: '', phone: '', gender: '', birthday: '' })
  modalOpen.value = true
}

function openEdit(user) {
  modalMode.value = 'edit'
  editingId.value = user.id
  Object.assign(form, {
    email: user.email || '', nickname: user.nickname || '', password: '', role: user.role || 'USER',
    enabled: Boolean(user.enabled), avatar: user.avatar || '', phone: user.phone || '',
    gender: user.gender || '', birthday: user.birthday ? String(user.birthday).slice(0, 10) : ''
  })
  modalOpen.value = true
}

async function save() {
  if (!form.email.trim()) { toast('邮箱不能为空', 'error'); return }
  if (modalMode.value === 'create' && form.password.length < 6) { toast('初始密码至少 6 位', 'error'); return }
  saving.value = true
  try {
    const payload = {
      email: form.email.trim(), nickname: form.nickname.trim(), password: form.password,
      role: form.role, enabled: form.enabled, avatar: form.avatar,
      phone: form.phone.trim(), gender: form.gender, birthday: form.birthday || null
    }
    if (modalMode.value === 'edit') await http.put(`/admin/user-manage/${editingId.value}`, payload)
    else await http.post('/admin/user-manage', payload)
    toast(modalMode.value === 'edit' ? '用户资料已更新' : '用户已创建')
    modalOpen.value = false
    load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function toggleBan(user) {
  const action = user.enabled ? '封禁' : '解封'
  if (!window.confirm(`确定${action}「${displayName(user)}」吗？${user.enabled ? '封禁后该账号将无法登录。' : ''}`)) return
  try {
    await http.put(`/admin/user-manage/${user.id}`, { enabled: !user.enabled })
    toast(user.enabled ? '已封禁该账号' : '已解封该账号')
    load()
  } catch (e) {
    toast(e.message, 'error')
  }
}

function pickAvatar() { avatarInput.value?.click() }

async function uploadAvatar(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  uploadingAvatar.value = true
  try {
    const res = await http.post('/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    form.avatar = res.data.url
    toast('头像已上传，保存后生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    uploadingAvatar.value = false
    event.target.value = ''
  }
}

function openMute(user) {
  muteTarget.value = user
  muteForm.duration = 7
  muteForm.reason = user.muteReason || ''
}

function localIso(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

async function saveMute() {
  if (!muteTarget.value) return
  muteSaving.value = true
  try {
    const until = Number(muteForm.duration) === 0
      ? '9999-12-31T23:59:59'
      : localIso(new Date(Date.now() + Number(muteForm.duration) * 24 * 60 * 60 * 1000))
    await http.put(`/admin/users/${muteTarget.value.id}/mute`, { mutedUntil: until, reason: muteForm.reason.trim() })
    toast('禁言状态已更新')
    muteTarget.value = null
    load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    muteSaving.value = false
  }
}

async function unmute(user) {
  try {
    await http.put(`/admin/users/${user.id}/mute`, { mutedUntil: null, reason: '' })
    toast('已解除禁言')
    load()
  } catch (e) {
    toast(e.message, 'error')
  }
}

watch(activeTab, () => load(1))
onMounted(() => load(1))
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">{{ tabMeta.label }} · 共 {{ users.total }} 人</span>
      <div class="adm-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading" @click="load(users.page)">刷新</button>
        <button class="btn btn-sm" type="button" @click="openCreate">添加用户</button>
      </div>
    </div>

    <section class="adm-mini-stats">
      <div><span>注册用户</span><strong>{{ stats.users ?? '—' }}</strong></div>
      <div><span>管理员</span><strong>{{ stats.admins ?? '—' }}</strong></div>
      <div><span>已封禁</span><strong>{{ stats.disabled ?? '—' }}</strong></div>
      <div><span>已禁言</span><strong>{{ stats.muted ?? '—' }}</strong></div>
    </section>

    <section class="adm-toolbar">
      <div class="adm-search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" />
        </svg>
        <input v-model="keyword" type="search" placeholder="搜索昵称、邮箱或手机号" @keyup.enter="applySearch" />
        <button v-if="keyword" type="button" class="btn-text" @click="keyword = ''; applySearch()">清除</button>
      </div>
      <button class="btn btn-ghost btn-sm" type="button" @click="applySearch">搜索</button>
    </section>

    <div v-if="loading" class="adm-loading">正在读取用户…</div>

    <section v-else class="adm-card adm-table-card">
      <div class="adm-table-wrap">
        <table class="table adm-table user-table">
          <thead>
            <tr><th>用户</th><th>角色</th><th>状态</th><th>注册时间</th><th class="right">操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="user in users.records" :key="user.id">
              <td>
                <div class="user-cell">
                  <span class="avatar sm">
                    <img v-if="user.avatar" :src="user.avatar" alt="" />
                    <template v-else>{{ initialOf(displayName(user)) }}</template>
                  </span>
                  <div class="user-cell-copy">
                    <strong>{{ displayName(user) }} <em v-if="user.id === state.user?.id">你</em></strong>
                    <span>{{ user.email }}</span>
                    <small v-if="user.phone">{{ user.phone }}</small>
                  </div>
                </div>
              </td>
              <td><span class="role-pill" :class="`role-${String(user.role).toLowerCase()}`">{{ user.roleName || user.role }}</span></td>
              <td>
                <div class="user-status-stack">
                  <span class="adm-status" :class="user.enabled ? 'is-published' : 'is-rejected'">{{ user.enabled ? '正常' : '已封禁' }}</span>
                  <span v-if="user.muted" class="adm-status is-pending">{{ muteLabel(user) }}</span>
                </div>
              </td>
              <td class="user-date">{{ formatDate(user.createdAt) }}</td>
              <td class="right">
                <div class="row-actions">
                  <button class="btn-text" type="button" @click="openEdit(user)">编辑</button>
                  <button v-if="user.muted" class="btn-text" type="button" @click="unmute(user)">解除禁言</button>
                  <button v-else class="btn-text" type="button" @click="openMute(user)">禁言</button>
                  <button class="btn-text" :class="{ danger: user.enabled }" type="button" :disabled="user.id === state.user?.id" @click="toggleBan(user)">{{ user.enabled ? '封禁' : '解封' }}</button>
                </div>
              </td>
            </tr>
            <tr v-if="!users.records.length"><td colspan="5" class="adm-empty">没有匹配的用户</td></tr>
          </tbody>
        </table>
      </div>
      <div class="adm-pager">
        <span>第 {{ users.page }} 页 · 共 {{ users.total }} 人</span>
        <div>
          <button class="btn btn-ghost btn-sm" type="button" :disabled="users.page <= 1 || loading" @click="load(users.page - 1)">上一页</button>
          <button class="btn btn-ghost btn-sm" type="button" :disabled="users.page * users.size >= users.total || loading" @click="load(users.page + 1)">下一页</button>
        </div>
      </div>
    </section>

    <div v-if="modalOpen" class="adm-modal-wrap" @click.self="!saving && (modalOpen = false)">
      <div class="adm-modal user-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">USER EDITOR</span><h3>{{ modalMode === 'edit' ? '编辑用户' : '添加新用户' }}</h3></div>
          <button class="btn-text" type="button" :disabled="saving" @click="modalOpen = false">关闭</button>
        </div>
        <div class="adm-modal-body">
          <div class="user-avatar-row">
            <span class="avatar lg">
              <img v-if="form.avatar" :src="form.avatar" alt="" />
              <template v-else>{{ initialOf(form.nickname || form.email) }}</template>
            </span>
            <div>
              <button class="btn btn-ghost btn-sm" type="button" :disabled="uploadingAvatar" @click="pickAvatar">{{ uploadingAvatar ? '上传中…' : '上传头像' }}</button>
              <button v-if="form.avatar" class="btn-text" type="button" @click="form.avatar = ''">移除</button>
              <p class="meta">头像会在用户列表、文章署名和个人主页显示。</p>
            </div>
          </div>
          <div class="user-form-grid">
            <div class="field span-2"><label>邮箱（也是登录账号）</label><input v-model="form.email" type="email" maxlength="128" placeholder="name@example.com" /></div>
            <div class="field"><label>昵称</label><input v-model="form.nickname" maxlength="32" placeholder="显示名称" /></div>
            <div class="field"><label>{{ modalMode === 'edit' ? '新密码（留空不改）' : '初始密码' }}</label><input v-model="form.password" type="password" autocomplete="new-password" :placeholder="modalMode === 'edit' ? '留空保持原密码' : '至少 6 位'" /></div>
            <div class="field"><label>角色</label><select v-model="form.role"><option v-for="role in roleOptions" :key="role.code" :value="role.code">{{ role.name }} · {{ role.code }}</option></select></div>
            <div class="field"><label>账号状态</label><label class="adm-check-line user-enable"><input v-model="form.enabled" type="checkbox" /> 允许登录</label></div>
            <div class="field"><label>手机号</label><input v-model="form.phone" maxlength="32" placeholder="可选" /></div>
            <div class="field"><label>性别</label><select v-model="form.gender"><option value="">未设置</option><option value="male">男</option><option value="female">女</option><option value="other">其他</option></select></div>
            <div class="field"><label>生日</label><input v-model="form.birthday" type="date" /></div>
          </div>
        </div>
        <div class="adm-modal-actions split">
          <button class="btn btn-ghost btn-sm" type="button" :disabled="saving" @click="modalOpen = false">取消</button>
          <button class="btn btn-sm" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : (modalMode === 'edit' ? '保存修改' : '创建用户') }}</button>
        </div>
      </div>
    </div>

    <div v-if="muteTarget" class="adm-modal-wrap" @click.self="!muteSaving && (muteTarget = null)">
      <div class="adm-modal mute-modal">
        <div class="adm-modal-head"><div><span class="adm-card-kicker">MUTE</span><h3>禁言 {{ displayName(muteTarget) }}</h3></div><button class="btn-text" type="button" @click="muteTarget = null">关闭</button></div>
        <div class="adm-modal-body">
          <div class="field"><label>禁言时长</label><select v-model="muteForm.duration"><option :value="1">1 天</option><option :value="3">3 天</option><option :value="7">7 天</option><option :value="30">30 天</option><option :value="0">永久</option></select></div>
          <div class="field"><label>原因</label><textarea v-model="muteForm.reason" rows="3" maxlength="512" placeholder="会记录在用户资料中"></textarea></div>
        </div>
        <div class="adm-modal-actions split"><button class="btn btn-ghost btn-sm" type="button" :disabled="muteSaving" @click="muteTarget = null">取消</button><button class="btn btn-sm" type="button" :disabled="muteSaving" @click="saveMute">{{ muteSaving ? '保存中…' : '确认禁言' }}</button></div>
      </div>
    </div>
    <input ref="avatarInput" type="file" accept="image/*" hidden @change="uploadAvatar" />
  </div>
</template>

<style scoped>
.user-cell { display: flex; align-items: center; gap: 11px; }
.user-cell-copy { display: grid; gap: 2px; min-width: 0; }
.user-cell-copy strong { display: flex; align-items: center; gap: 6px; color: var(--ink); font-size: 13px; }
.user-cell-copy em { padding: 1px 5px; border-radius: 4px; background: var(--accent-soft); color: var(--accent-ink); font-size: 9px; font-style: normal; }
.user-cell-copy span { color: var(--muted); font-size: 11px; overflow: hidden; text-overflow: ellipsis; }
.user-cell-copy small { color: var(--muted); font-size: 10px; }
.role-pill { display: inline-block; padding: 3px 8px; border-radius: 999px; background: var(--line); color: var(--ink-soft); font-size: 11px; }
.role-pill.role-admin { background: #eef2ff; color: #3730a3; }
[data-theme="dark"] .role-pill.role-admin { background: #232a55; color: #c7d2fe; }
.role-pill.role-editor { background: #ecfdf5; color: #047857; }
[data-theme="dark"] .role-pill.role-editor { background: #123c32; color: #a7f3d0; }
.user-status-stack { display: grid; gap: 4px; }
.user-date { color: var(--muted); font-size: 12px; white-space: nowrap; }
.row-actions { display: flex; gap: 9px; justify-content: flex-end; flex-wrap: wrap; }
.btn-text.danger { color: #b91c1c; }
.adm-pager { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-top: 1px solid var(--line); color: var(--muted); font-size: 12px; }
.user-modal { width: min(720px, calc(100vw - 32px)); }
.user-avatar-row { display: flex; align-items: center; gap: 16px; margin-bottom: 18px; padding: 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--accent-soft); }
.user-avatar-row .meta { margin: 6px 0 0; color: var(--muted); font-size: 11px; }
.user-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; }
.user-form-grid .span-2 { grid-column: span 2; }
.user-enable { margin: 0; padding: 8px 10px; border: 1px solid var(--line); border-radius: 7px; }
.mute-modal { width: min(460px, calc(100vw - 32px)); }
@media (max-width: 720px) { .user-form-grid { grid-template-columns: 1fr; } .user-form-grid .span-2 { grid-column: auto; } .user-table { min-width: 760px; } }
</style>
