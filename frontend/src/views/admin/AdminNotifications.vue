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

const filtered = computed(() => {
  if (activeTab.value === 'published') return items.value.filter((n) => n.published)
  if (activeTab.value === 'draft') return items.value.filter((n) => !n.published)
  return items.value
})

const publishedCount = computed(() => items.value.filter((n) => n.published).length)

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

onMounted(load)
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
              <td colspan="6"><div class="adm-empty">当前筛选下没有通知。新建一条通知后，首页铃铛会显示。</div></td>
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
