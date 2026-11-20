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
const stats = ref(null)
const loading = ref(false)
const selectedUser = ref(null)
const muteTarget = ref(null)
const muteSaving = ref(false)
const muteForm = reactive({ duration: 7, reason: '' })

const tabMeta = computed(() => ({
  all: { label: '全部用户', desc: '所有注册账号' },
  admins: { label: '管理员', desc: '拥有后台权限的账号' },
  banned: { label: '已封禁', desc: '无法登录的账号' },
  muted: { label: '已禁言', desc: '可登录但不能评论' }
}[activeTab.value] || { label: '全部用户', desc: '所有注册账号' }))

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
    const res = await http.get('/admin/users', { params: params(page) })
    users.records = res.data.records
    users.total = res.data.total
    users.page = res.data.page
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = (await http.get('/admin/stats')).data
  } catch (e) {
    /* 统计失败不阻塞列表 */
  }
}

function applySearch() {
  load(1)
}

function displayName(user) {
  return user.nickname || user.username
}

function muteLabel(user) {
  if (!user.muted) return ''
  if (!user.mutedUntil || String(user.mutedUntil).startsWith('9999')) return '永久禁言'
  return `禁言至 ${formatDateTime(user.mutedUntil)}`
}

async function toggleBan(user) {
  const action = user.enabled ? '封禁' : '解封'
  if (!window.confirm(`确定${action}「${displayName(user)}」吗？${user.enabled ? '封禁后该账号将无法登录。' : ''}`)) return
  try {
    await http.put(`/admin/users/${user.id}/status`, { enabled: !user.enabled })
    toast(user.enabled ? '已封禁该账号' : '已解封该账号')
    load()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function changeRole(user) {
  const next = user.role === 'ADMIN' ? 'USER' : 'ADMIN'
  if (!window.confirm(`确定将「${displayName(user)}」${next === 'ADMIN' ? '设为管理员' : '降为读者'}吗？`)) return
  try {
    await http.put(`/admin/users/${user.id}/role`, { role: next })
    toast('角色权限已更新')
    load()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  }
}

function openMute(user) {
  muteTarget.value = user
  muteForm.duration = user.muted ? 7 : 7
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
    await http.put(`/admin/users/${muteTarget.value.id}/mute`, {
      mutedUntil: until,
      reason: muteForm.reason.trim()
    })
    toast('禁言状态已更新')
    muteTarget.value = null
    load()
    loadStats()
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
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  }
}

watch(activeTab, () => load(1))
onMounted(() => {
  load(1)
  loadStats()
})
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">{{ tabMeta.label }} · 共 {{ users.total }} 人</span>
    </div>

    <section class="adm-mini-stats">
      <div><span>注册用户</span><strong>{{ stats?.users ?? '—' }}</strong></div>
      <div><span>管理员</span><strong>{{ stats?.admins ?? '—' }}</strong></div>
      <div><span>已封禁</span><strong>{{ stats?.disabledUsers ?? '—' }}</strong></div>
      <div><span>已禁言</span><strong>{{ stats?.mutedUsers ?? '—' }}</strong></div>
    </section>

    <section class="adm-toolbar">
      <div class="adm-search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-4-4" />
        </svg>
        <input v-model="keyword" type="search" placeholder="搜索昵称、账号或邮箱" @keyup.enter="applySearch" />
        <button v-if="keyword" type="button" class="btn-text" @click="keyword = ''; applySearch()">清除</button>
      </div>
      <button class="btn btn-ghost btn-sm" type="button" @click="applySearch">搜索</button>
    </section>

    <div v-if="loading" class="adm-loading">正在读取用户…</div>

    <section v-else class="adm-card adm-table-card">
      <div class="adm-table-wrap">
        <table class="table adm-table">
          <thead>
            <tr>
              <th>用户</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>账号状态</th>
              <th>评论权限</th>
              <th>注册时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users.records" :key="user.id" :class="{ 'is-self': user.id === state.user?.id }">
              <td>
                <button type="button" class="adm-user-cell" @click="selectedUser = user">
                  <span class="avatar">
                    <img v-if="user.avatar" :src="user.avatar" alt="" />
                    <template v-else>{{ initialOf(displayName(user)) }}</template>
                  </span>
                  <span>
                    <strong>{{ displayName(user) }}</strong>
                    <small>@{{ user.username }}<em v-if="user.id === state.user?.id">自己</em></small>
                  </span>
                </button>
              </td>
              <td class="adm-email-cell">{{ user.email }}</td>
              <td>
                <span class="adm-status" :class="user.role === 'ADMIN' ? 'is-admin' : 'is-user'">{{ user.role === 'ADMIN' ? '管理员' : '读者' }}</span>
              </td>
              <td>
                <span class="adm-status" :class="user.enabled ? 'is-published' : 'is-rejected'">{{ user.enabled ? '正常' : '已封禁' }}</span>
              </td>
              <td>
                <span v-if="user.muted" class="adm-status is-pending">{{ muteLabel(user) }}</span>
                <span v-else class="adm-muted-text">正常</span>
              </td>
              <td>{{ formatDate(user.createdAt) }}</td>
              <td>
                <div class="adm-row-actions">
                  <button class="btn-text" type="button" @click="selectedUser = user">详情</button>
                  <button class="btn-text" type="button" :disabled="user.id === state.user?.id" @click="changeRole(user)">
                    {{ user.role === 'ADMIN' ? '降为读者' : '设为管理员' }}
                  </button>
                  <button class="btn-text" type="button" :disabled="user.id === state.user?.id" @click="user.muted ? unmute(user) : openMute(user)">
                    {{ user.muted ? '解除禁言' : '禁言' }}
                  </button>
                  <button class="btn-text danger" type="button" :disabled="user.id === state.user?.id" @click="toggleBan(user)">
                    {{ user.enabled ? '封禁' : '解封' }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!users.records.length">
              <td colspan="7" class="adm-empty-cell">没有符合条件的用户</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="users.total > users.size" class="pager">
        <button type="button" :disabled="users.page <= 1" @click="load(users.page - 1)">上一页</button>
        <span class="meta">第 {{ users.page }} 页 / 共 {{ users.total }} 人</span>
        <button type="button" :disabled="users.page * users.size >= users.total" @click="load(users.page + 1)">下一页</button>
      </div>
    </section>

    <div v-if="selectedUser" class="adm-drawer-wrap" @click.self="selectedUser = null">
      <aside class="adm-drawer">
        <button type="button" class="adm-drawer-close" aria-label="关闭" @click="selectedUser = null">×</button>
        <span class="adm-eyebrow">USER DETAIL</span>
        <div class="adm-drawer-user">
          <span class="avatar lg">
            <img v-if="selectedUser.avatar" :src="selectedUser.avatar" alt="" />
            <template v-else>{{ initialOf(displayName(selectedUser)) }}</template>
          </span>
          <h2>{{ displayName(selectedUser) }}</h2>
          <p>@{{ selectedUser.username }}</p>
        </div>
        <dl class="adm-facts">
          <div><dt>邮箱</dt><dd>{{ selectedUser.email }}</dd></div>
          <div><dt>角色</dt><dd>{{ selectedUser.role === 'ADMIN' ? '管理员' : '读者' }}</dd></div>
          <div><dt>账号状态</dt><dd>{{ selectedUser.enabled ? '正常' : '已封禁' }}</dd></div>
          <div><dt>评论权限</dt><dd>{{ selectedUser.muted ? muteLabel(selectedUser) : '正常' }}</dd></div>
          <div v-if="selectedUser.muteReason"><dt>禁言原因</dt><dd>{{ selectedUser.muteReason }}</dd></div>
          <div><dt>注册时间</dt><dd>{{ formatDateTime(selectedUser.createdAt) }}</dd></div>
        </dl>
        <div class="adm-drawer-bio">
          <span>个人简介</span>
          <p>{{ selectedUser.bio || '还没有填写个人简介。' }}</p>
        </div>
        <div class="adm-drawer-actions">
          <button class="btn btn-ghost btn-sm" type="button" :disabled="selectedUser.id === state.user?.id" @click="changeRole(selectedUser)">{{ selectedUser.role === 'ADMIN' ? '降为读者' : '设为管理员' }}</button>
          <button class="btn btn-ghost btn-sm" type="button" :disabled="selectedUser.id === state.user?.id" @click="selectedUser.muted ? unmute(selectedUser) : openMute(selectedUser)">{{ selectedUser.muted ? '解除禁言' : '禁言' }}</button>
          <button class="btn btn-danger btn-sm" type="button" :disabled="selectedUser.id === state.user?.id" @click="toggleBan(selectedUser)">{{ selectedUser.enabled ? '封禁账号' : '解封账号' }}</button>
        </div>
      </aside>
    </div>

    <div v-if="muteTarget" class="adm-modal-wrap" @click.self="muteTarget = null">
      <div class="adm-modal">
        <div class="adm-modal-head">
          <div>
            <span class="adm-eyebrow">MUTE</span>
            <h2>禁言 {{ displayName(muteTarget) }}</h2>
          </div>
          <button type="button" class="adm-drawer-close" aria-label="关闭" @click="muteTarget = null">×</button>
        </div>
        <p class="adm-note">禁言只限制评论与回复，不影响登录、浏览和收藏。</p>
        <div class="form-grid">
          <div class="field">
            <label for="muteDuration">禁言时长</label>
            <select id="muteDuration" v-model.number="muteForm.duration">
              <option :value="1">1 天</option>
              <option :value="7">7 天</option>
              <option :value="30">30 天</option>
              <option :value="0">永久</option>
            </select>
          </div>
          <div class="field">
            <label>生效范围</label>
            <input value="评论区 / 回复" type="text" disabled />
          </div>
          <div class="field span-2">
            <label for="muteReason">禁言原因</label>
            <textarea id="muteReason" v-model="muteForm.reason" maxlength="512" style="min-height: 90px" placeholder="例如：发布广告、恶意引战、刷屏"></textarea>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="muteTarget = null">取消</button>
          <button class="btn" type="button" :disabled="muteSaving" @click="saveMute">{{ muteSaving ? '提交中…' : '确认禁言' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
