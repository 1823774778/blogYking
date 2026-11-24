<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'
import { formatDateTime } from '../../utils'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'all'))
const items = ref([])
const loading = ref(false)
const modalOpen = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = reactive({
  title: '',
  content: '',
  type: 'INFO',
  pinned: false,
  published: true
})

const keyword = ref('')
const typeFilter = ref('all')
const filtered = computed(() => {
  let list = items.value
  if (activeTab.value === 'published') list = list.filter((n) => n.published)
  if (activeTab.value === 'draft') list = list.filter((n) => !n.published)
  if (typeFilter.value !== 'all') list = list.filter((n) => n.type === typeFilter.value)
  const q = keyword.value.trim().toLowerCase()
  if (q) {
    list = list.filter((n) => `${n.title || ''} ${n.content || ''}`.toLowerCase().includes(q))
  }
  return list
})

const publishedCount = computed(() => items.value.filter((n) => n.published).length)
const prefsSaving = ref(false)
const testingNotice = ref(false)
const notifyPrefs = reactive({
  notifyCommentsEnabled: 'true',
  notifyLikesEnabled: 'true',
  notifyFavoritesEnabled: 'true',
  notifyUpdatesEnabled: 'true',
  notifyNewUsersEnabled: 'true',
  notifyPendingCommentsOnly: 'false'
})
const notifyOptions = [
  { key: 'notifyCommentsEnabled', title: '新评论提醒', desc: '读者评论或回复文章时记录到互动通知。' },
  { key: 'notifyLikesEnabled', title: '喜欢提醒', desc: '文章收到新的喜欢时提醒。' },
  { key: 'notifyFavoritesEnabled', title: '收藏提醒', desc: '文章被读者收藏时提醒。' },
  { key: 'notifyUpdatesEnabled', title: '版本更新提醒', desc: '站点升级完成后写入一条系统通知。' },
  { key: 'notifyNewUsersEnabled', title: '新用户提醒', desc: '有新账号注册或提交注册申请时提醒。' }
]

async function load() {
  loading.value = true
  try {
    items.value = (await http.get('/admin/notifications')).data || []
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function loadPrefs() {
  try {
    const cfg = (await http.get('/admin/config')).data || {}
    for (const key of Object.keys(notifyPrefs)) {
      notifyPrefs[key] = String(cfg[key] ?? (key === 'notifyPendingCommentsOnly' ? 'false' : 'true'))
    }
  } catch (e) {
    toast(e.message, 'error')
  }
}

function booleanValue(value) {
  return String(value).toLowerCase() === 'true'
}

function togglePref(key, event) {
  notifyPrefs[key] = event.target.checked ? 'true' : 'false'
}

async function savePrefs() {
  prefsSaving.value = true
  try {
    const res = (await http.put('/admin/config', { ...notifyPrefs })).data || {}
    for (const key of Object.keys(notifyPrefs)) {
      notifyPrefs[key] = String(res[key] ?? notifyPrefs[key])
    }
    toast('提醒偏好已保存')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    prefsSaving.value = false
  }
}

async function sendTestNotice() {
  testingNotice.value = true
  try {
    await http.post('/admin/activity-notices/test')
    toast('测试提醒已发送，可在仪表盘互动通知中查看')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    testingNotice.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { title: '', content: '', type: 'INFO', pinned: false, published: true })
  modalOpen.value = true
}

function openEdit(item) {
  editingId.value = item.id
  Object.assign(form, {
    title: item.title,
    content: item.content,
    type: item.type,
    pinned: item.pinned,
    published: item.published
  })
  modalOpen.value = true
}

async function save() {
  if (!form.title.trim() || !form.content.trim()) {
    toast('请填写通知标题和内容', 'error')
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      content: form.content.trim(),
      type: form.type,
      pinned: form.pinned,
      published: form.published
    }
    if (editingId.value) {
      await http.put(`/admin/notifications/${editingId.value}`, payload)
      toast('通知已更新')
    } else {
      await http.post('/admin/notifications', payload)
      toast('通知已创建')
    }
    modalOpen.value = false
    load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function togglePublished(item) {
  try {
    await http.put(`/admin/notifications/${item.id}`, {
      title: item.title,
      content: item.content,
      type: item.type,
      pinned: item.pinned,
      published: !item.published
    })
    item.published = !item.published
    toast(item.published ? '通知已发布到首页' : '通知已撤下')
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function remove(item) {
  if (!window.confirm(`确定删除通知“${item.title}”吗？`)) return
  try {
    await http.delete(`/admin/notifications/${item.id}`)
    toast('通知已删除')
    load()
  } catch (e) {
    toast(e.message, 'error')
  }
}

onMounted(() => { load(); loadPrefs() })
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <div class="adm-head-actions">
        <span class="adm-total-pill">通知设置 · {{ items.length }}</span>
        <span class="meta">{{ publishedCount }} 条已发布</span>
      </div>
      <div class="adm-head-actions">
        <button class="btn btn-sm" type="button" @click="openCreate">新建通知</button>
      </div>
    </div>

    <section class="adm-card notify-pref-card">
      <div class="adm-card-head">
        <div>
          <span class="adm-card-kicker">REMINDER POLICY</span>
          <h2>提醒偏好</h2>
          <p>决定哪些互动会进入仪表盘的互动通知。站内通知不受邮件服务影响；关闭某类提醒后，新事件会被直接过滤。</p>
        </div>
        <router-link class="adm-total-pill notify-pref-link" to="/admin">查看互动通知</router-link>
      </div>
      <div class="notify-pref-grid">
        <label v-for="item in notifyOptions" :key="item.key" class="adm-switch-label notify-pref-row">
          <span><strong>{{ item.title }}</strong><small>{{ item.desc }}</small></span>
          <input type="checkbox" :checked="booleanValue(notifyPrefs[item.key])" @change="togglePref(item.key, $event)" />
          <i class="adm-switch" aria-hidden="true"></i>
        </label>
      </div>
      <label class="adm-switch-label notify-pref-row notify-pref-sub">
        <span><strong>仅提醒待审核评论</strong><small>开启后，直接展示的评论不再打扰，只记录需要审核的评论。</small></span>
        <input type="checkbox" :checked="booleanValue(notifyPrefs.notifyPendingCommentsOnly)" :disabled="!booleanValue(notifyPrefs.notifyCommentsEnabled)" @change="togglePref('notifyPendingCommentsOnly', $event)" />
        <i class="adm-switch" aria-hidden="true"></i>
      </label>
      <div class="notify-pref-foot">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="testingNotice" @click="sendTestNotice">{{ testingNotice ? '发送中…' : '发送测试提醒' }}</button>
        <button class="btn btn-sm" type="button" :disabled="prefsSaving" @click="savePrefs">{{ prefsSaving ? '保存中…' : '保存提醒偏好' }}</button>
      </div>
    </section>

    <section class="adm-toolbar notify-toolbar">
      <div class="adm-search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" /></svg>
        <input v-model="keyword" type="search" placeholder="搜索通知标题或内容" />
        <button v-if="keyword" class="btn-text" type="button" @click="keyword = ''">清除</button>
      </div>
      <select v-model="typeFilter" class="notify-type-filter" aria-label="通知类型筛选">
        <option value="all">全部类型</option>
        <option value="INFO">通知</option>
        <option value="UPDATE">更新</option>
        <option value="SYSTEM">系统</option>
      </select>
      <button v-if="keyword || typeFilter !== 'all'" class="btn btn-ghost btn-sm" type="button" @click="keyword = ''; typeFilter = 'all'">重置筛选</button>
    </section>

    <section class="adm-card adm-callout">
      <div>
        <span class="adm-card-kicker">NOTIFICATIONS</span>
        <h2>首页通知</h2>
        <p>发布后的通知会出现在首页顶部导航的铃铛按钮中。置顶通知排在最前，未发布的通知只保存在后台。</p>
      </div>
    </section>

    <div v-if="loading" class="adm-loading">正在读取通知…</div>

    <section v-else class="adm-card adm-table-card">
      <div class="adm-table-wrap">
        <table class="table adm-table">
          <thead>
            <tr>
              <th>通知</th>
              <th>类型</th>
              <th>状态</th>
              <th>置顶</th>
              <th>创建时间</th>
              <th class="align-right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filtered" :key="item.id">
              <td>
                <div class="notice-admin-title"><strong>{{ item.title }}</strong><span>{{ item.content }}</span></div>
              </td>
              <td><span class="badge">{{ item.type === 'UPDATE' ? '更新' : item.type === 'SYSTEM' ? '系统' : '通知' }}</span></td>
              <td>
                <button class="badge" :class="item.published ? 'on' : 'off'" type="button" @click="togglePublished(item)">{{ item.published ? '已发布' : '未发布' }}</button>
              </td>
              <td>{{ item.pinned ? '是' : '—' }}</td>
              <td class="meta">{{ formatDateTime(item.createdAt) }}</td>
              <td class="align-right">
                <div class="adm-row-actions">
                  <button class="btn-text" type="button" @click="openEdit(item)">编辑</button>
                  <button class="btn-text danger" type="button" @click="remove(item)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="6"><div class="adm-empty">当前筛选下没有通知。调整搜索条件，或新建一条通知。</div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="modalOpen" class="adm-modal-wrap" @click.self="modalOpen = false">
      <div class="adm-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">NOTICE</span><h2>{{ editingId ? '编辑通知' : '新建通知' }}</h2><p class="meta">通知保存在数据库，由后端读取后展示在首页导航。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="modalOpen = false">×</button>
        </div>
        <div class="form-grid">
          <div class="field span-2">
            <label for="noticeTitle">标题</label>
            <input id="noticeTitle" v-model="form.title" type="text" maxlength="160" placeholder="例如：博客已升级到 1.5.0" />
          </div>
          <div class="field">
            <label for="noticeType">类型</label>
            <select id="noticeType" v-model="form.type">
              <option value="INFO">通知</option>
              <option value="UPDATE">更新</option>
              <option value="SYSTEM">系统</option>
            </select>
          </div>
          <div class="field">
            <label>发布状态</label>
            <label class="adm-switch-label">
              <span>立即发布</span>
              <input v-model="form.published" type="checkbox" />
              <i class="adm-switch" aria-hidden="true"></i>
            </label>
          </div>
          <div class="field span-2">
            <label for="noticeContent">内容</label>
            <textarea id="noticeContent" v-model="form.content" maxlength="2000" style="min-height: 140px"></textarea>
          </div>
          <div class="field span-2">
            <label class="adm-switch-label">
              <span>置顶到通知列表最前</span>
              <input v-model="form.pinned" type="checkbox" />
              <i class="adm-switch" aria-hidden="true"></i>
            </label>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="saving" @click="modalOpen = false">取消</button>
          <button class="btn" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存通知' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notify-pref-link { text-decoration: none; }
.notify-pref-link:hover { border-color: var(--accent); color: var(--accent); }
.notify-pref-card { margin-bottom: 16px; }
.notify-pref-card .adm-card-head { align-items: flex-start; }
.notify-pref-card .adm-card-head p { max-width: 760px; margin: 6px 0 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.notify-pref-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 18px; margin-top: 16px; }
.notify-pref-row { position: relative; display: flex; align-items: center; gap: 10px; min-height: 54px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; background: color-mix(in srgb, var(--paper) 78%, var(--bg)); cursor: pointer; }
.notify-pref-row > span { display: grid; gap: 3px; flex: 1; min-width: 0; }
.notify-pref-row strong { color: var(--ink); font-size: 12.5px; }
.notify-pref-row small { color: var(--muted); font-size: 10.5px; line-height: 1.55; }
.notify-pref-row input { position: absolute; opacity: 0; pointer-events: none; }
.notify-pref-sub { margin-top: 8px; min-height: 50px; }
.notify-pref-row:has(input:disabled) { opacity: .55; cursor: not-allowed; }
.notify-pref-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.notify-toolbar { margin: 16px 0 14px; }
.notify-type-filter { min-height: 38px; padding: 0 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--paper); color: var(--ink); font-size: 12px; }
@media (max-width: 780px) { .notify-pref-grid { grid-template-columns: 1fr; } .notify-toolbar { flex-wrap: wrap; } }
</style>
