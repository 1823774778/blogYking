<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import http from '../../api'
import { state, toast } from '../../state'

const items = ref([])
const loading = ref(false)
const saving = ref(false)
const modalOpen = ref(false)
const editingIndex = ref(null)
const form = reactive({ label: '', path: '', visible: true, newTab: false })

const visibleCount = computed(() => items.value.filter((item) => item.visible !== false).length)

function isExternal(path) {
  return /^https?:\/\//i.test(String(path || ''))
}

async function load() {
  loading.value = true
  try {
    items.value = (await http.get('/admin/navigation')).data || []
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingIndex.value = null
  Object.assign(form, { label: '', path: '', visible: true, newTab: false })
  modalOpen.value = true
}

function openEdit(index, item) {
  editingIndex.value = index
  Object.assign(form, {
    label: item.label || '',
    path: item.path || '',
    visible: item.visible !== false,
    newTab: Boolean(item.newTab)
  })
  modalOpen.value = true
}

async function persist(next, message) {
  if (saving.value) return false
  saving.value = true
  try {
    const res = await http.put('/admin/navigation', next)
    items.value = res.data || []
    state.site.frontNavItems = JSON.stringify(items.value)
    if (message) toast(message)
    return true
  } catch (e) {
    toast(e.message, 'error')
    return false
  } finally {
    saving.value = false
  }
}

async function saveItem() {
  const label = form.label.trim()
  const path = form.path.trim()
  if (!label) {
    toast('请填写导航标签名称', 'error')
    return
  }
  if (!path) {
    toast('请填写跳转地址', 'error')
    return
  }
  const payload = {
    label,
    path,
    visible: form.visible,
    newTab: form.newTab
  }
  const next = items.value.map((item) => ({ ...item }))
  if (editingIndex.value === null) {
    next.push(payload)
  } else {
    next[editingIndex.value] = payload
  }
  if (await persist(next, editingIndex.value === null ? '导航标签已添加' : '导航标签已更新')) {
    modalOpen.value = false
  }
}

async function toggleVisible(index, item) {
  const next = items.value.map((entry) => ({ ...entry }))
  next[index] = { ...next[index], visible: item.visible === false }
  await persist(next, next[index].visible ? '导航标签已显示' : '导航标签已隐藏')
}

async function move(index, delta) {
  const target = index + delta
  if (target < 0 || target >= items.value.length) return
  const next = items.value.map((item) => ({ ...item }))
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  await persist(next, '导航顺序已更新')
}

async function remove(index) {
  const item = items.value[index]
  if (!window.confirm(`确定删除前台导航标签「${item.label}」吗？`)) return
  const next = items.value.filter((_, position) => position !== index).map((entry) => ({ ...entry }))
  await persist(next, '导航标签已删除')
}

onMounted(load)
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <div class="adm-head-actions">
        <span class="adm-total-pill">导航标签 · {{ items.length }}</span>
        <span class="meta">{{ visibleCount }} 个前台显示</span>
      </div>
      <div class="adm-head-actions">
        <button class="btn btn-sm" type="button" :disabled="saving" @click="openCreate">新增导航标签</button>
      </div>
    </div>

    <section class="adm-card adm-callout">
      <div>
        <span class="adm-card-kicker">NAVIGATION</span>
        <h2>顶部导航标签</h2>
        <p>管理前台顶部导航栏里的标签。站内地址以 / 开头，外部链接以 http:// 或 https:// 开头；最多 8 个，保存后立即生效。</p>
      </div>
    </section>

    <div v-if="loading" class="adm-loading">正在读取导航标签…</div>

    <section v-else class="adm-card adm-table-card">
      <div class="adm-table-wrap">
        <table class="table adm-table nav-admin-table">
          <thead>
            <tr>
              <th>顺序</th>
              <th>标签</th>
              <th>跳转地址</th>
              <th>前台显示</th>
              <th>打开方式</th>
              <th class="align-right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in items" :key="`${item.path}-${index}`">
              <td><span class="nav-admin-position">{{ index + 1 }}</span></td>
              <td>
                <div class="nav-admin-label">
                  <span class="nav-admin-icon" aria-hidden="true">{{ isExternal(item.path) ? '外' : '内' }}</span>
                  <div>
                    <strong>{{ item.label }}</strong>
                    <small>{{ isExternal(item.path) ? '外部链接' : '站内页面' }}</small>
                  </div>
                </div>
              </td>
              <td><code class="nav-admin-path" :title="item.path">{{ item.path }}</code></td>
              <td>
                <button class="badge" :class="item.visible !== false ? 'on' : 'off'" type="button" :disabled="saving" @click="toggleVisible(index, item)">
                  {{ item.visible !== false ? '显示' : '隐藏' }}
                </button>
              </td>
              <td><span class="nav-open-mode">{{ item.newTab ? '新窗口' : '当前窗口' }}</span></td>
              <td class="align-right">
                <div class="adm-row-actions nav-row-actions">
                  <button class="btn-text nav-move" type="button" :disabled="saving || index === 0" aria-label="上移" title="上移" @click="move(index, -1)">↑</button>
                  <button class="btn-text nav-move" type="button" :disabled="saving || index === items.length - 1" aria-label="下移" title="下移" @click="move(index, 1)">↓</button>
                  <button class="btn-text" type="button" :disabled="saving" @click="openEdit(index, item)">编辑</button>
                  <button class="btn-text danger" type="button" :disabled="saving" @click="remove(index)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!items.length">
              <td colspan="6"><div class="adm-empty">还没有前台导航标签，点击右上角新增。</div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="modalOpen" class="adm-modal-wrap" @click.self="modalOpen = false">
      <div class="adm-modal nav-admin-modal" role="dialog" aria-modal="true" aria-labelledby="navDialogTitle">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">NAV ITEM</span><h2 id="navDialogTitle">{{ editingIndex === null ? '新增导航标签' : '编辑导航标签' }}</h2></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="modalOpen = false">×</button>
        </div>
        <div class="form-grid">
          <div class="field">
            <label for="navLabel">标签名称</label>
            <input id="navLabel" v-model="form.label" type="text" maxlength="24" placeholder="例如：归档" />
          </div>
          <div class="field">
            <label for="navPath">跳转地址</label>
            <input id="navPath" v-model="form.path" type="text" maxlength="300" placeholder="/archive 或 https://example.com" />
            <p class="meta">站内地址必须以 / 开头；外部链接使用完整 http(s) 地址。</p>
          </div>
          <div class="field">
            <label class="adm-switch-label">
              <span>在前台显示</span>
              <input v-model="form.visible" type="checkbox" />
              <i class="adm-switch" aria-hidden="true"></i>
            </label>
          </div>
          <div class="field">
            <label class="adm-switch-label">
              <span>在新窗口打开</span>
              <input v-model="form.newTab" type="checkbox" />
              <i class="adm-switch" aria-hidden="true"></i>
            </label>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="saving" @click="modalOpen = false">取消</button>
          <button class="btn" type="button" :disabled="saving" @click="saveItem">{{ saving ? '保存中…' : '保存导航标签' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.nav-admin-table { min-width: 760px; }
.nav-admin-position { display: grid; place-items: center; width: 26px; height: 26px; border-radius: 7px; background: var(--accent-soft); color: var(--accent-ink); font-family: var(--mono); font-size: 10px; font-weight: 700; }
.nav-admin-label { display: flex; align-items: center; gap: 9px; min-width: 0; }
.nav-admin-icon { display: grid; place-items: center; width: 28px; height: 28px; flex: 0 0 28px; border-radius: 8px; background: color-mix(in srgb, var(--accent) 9%, var(--paper)); color: var(--accent-ink); font-size: 13px; font-weight: 800; }
.nav-admin-label > div { display: grid; gap: 2px; min-width: 0; }
.nav-admin-label strong { overflow: hidden; color: var(--ink); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.nav-admin-label small { color: var(--muted); font-size: 10px; }
.nav-admin-path { display: block; max-width: 360px; overflow: hidden; color: var(--muted); font-family: var(--mono); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.nav-open-mode { color: var(--muted); font-size: 11px; white-space: nowrap; }
.nav-row-actions { gap: 5px; }
.nav-move { width: 24px; padding: 2px; font-family: var(--mono); font-size: 13px; }
.nav-admin-modal { width: min(640px, calc(100vw - 32px)); }
@media (max-width: 760px) {
  .nav-admin-path { max-width: 220px; }
}
</style>
