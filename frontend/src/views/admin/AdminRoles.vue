<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'all'))
const roles = ref([])
const permissions = ref([])
const loading = ref(false)
const saving = ref(false)
const modalOpen = ref(false)
const editingId = ref(null)
const form = reactive({ code: '', name: '', description: '', permissions: [], enabled: true, builtin: false })

const permissionGroups = computed(() => {
  const groups = new Map()
  for (const p of permissions.value) {
    if (!groups.has(p.group)) groups.set(p.group, [])
    groups.get(p.group).push(p)
  }
  return [...groups.entries()].map(([group, items]) => ({ group, items }))
})

const adminRole = computed(() => roles.value.find((r) => r.code === 'ADMIN'))

async function load() {
  loading.value = true
  try {
    const [rolesRes, permsRes] = await Promise.all([
      http.get('/admin/roles'),
      http.get('/admin/roles/permissions')
    ])
    roles.value = rolesRes.data || []
    permissions.value = permsRes.data || []
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { code: '', name: '', description: '', permissions: [], enabled: true, builtin: false })
  modalOpen.value = true
}

function openEdit(role) {
  editingId.value = role.id
  Object.assign(form, {
    code: role.code,
    name: role.name,
    description: role.description || '',
    permissions: [...(role.permissions || [])],
    enabled: role.enabled,
    builtin: role.builtin
  })
  modalOpen.value = true
}

function togglePermission(code) {
  if (form.permissions.includes(code)) form.permissions = form.permissions.filter((p) => p !== code)
  else form.permissions = [...form.permissions, code]
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    toast('角色编码和名称不能为空', 'error')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code.trim().toUpperCase(),
      name: form.name.trim(),
      description: form.description.trim(),
      permissions: form.permissions,
      enabled: form.enabled
    }
    if (editingId.value) await http.put(`/admin/roles/${editingId.value}`, payload)
    else await http.post('/admin/roles', payload)
    toast(editingId.value ? '角色已更新' : '角色已创建')
    modalOpen.value = false
    load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function remove(role) {
  if (role.builtin) return
  if (!window.confirm(`确定删除角色「${role.name}」吗？已分配该角色的用户需要重新选择角色。`)) return
  try {
    await http.delete(`/admin/roles/${role.id}`)
    toast('角色已删除')
    load()
  } catch (e) {
    toast(e.message, 'error')
  }
}

function permissionLabel(code) {
  return permissions.value.find((p) => p.code === code)?.label || code
}
onMounted(load)
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">{{ roles.length }} 个角色 · 权限由角色统一授予</span>
      <div class="adm-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading" @click="load">刷新</button>
        <button class="btn btn-sm" type="button" @click="openCreate">新建角色</button>
      </div>
    </div>

    <div v-if="loading" class="adm-loading">正在读取角色…</div>

    <template v-if="activeTab === 'permissions'">
      <section class="role-intro">
        <div><span class="adm-card-kicker">PERMISSION CATALOG</span><h2>权限目录</h2><p>权限用于控制后台模块访问。至少授予 `system:admin` 才能进入控制台。</p></div>
        <span class="role-admin-hint">当前管理员角色：{{ adminRole?.name || '管理员' }}</span>
      </section>
      <div class="perm-groups">
        <section v-for="group in permissionGroups" :key="group.group" class="adm-card perm-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">GROUP</span><h2>{{ group.group }}</h2></div></div>
          <div class="perm-list">
            <div v-for="p in group.items" :key="p.code" class="perm-row">
              <code>{{ p.code }}</code>
              <span>{{ p.label }}</span>
            </div>
          </div>
        </section>
      </div>
    </template>

    <template v-else>
      <section class="role-grid">
        <article v-for="role in roles" :key="role.id" class="role-card" :class="{ builtin: role.builtin, disabled: !role.enabled }">
          <div class="role-card-top">
            <span class="role-emblem" :class="`emblem-${role.code.toLowerCase()}`">{{ role.name.slice(0, 1) }}</span>
            <div class="role-title">
              <h3>{{ role.name }}</h3>
              <code>{{ role.code }}</code>
            </div>
            <span class="role-status" :class="{ on: role.enabled }">{{ role.enabled ? '启用' : '停用' }}</span>
          </div>
          <p class="role-desc">{{ role.description || '未填写角色描述' }}</p>
          <div class="role-perms">
            <span v-for="code in (role.permissions || []).slice(0, 4)" :key="code" class="perm-chip">{{ permissionLabel(code) }}</span>
            <span v-if="!role.permissions?.length" class="perm-chip empty">无后台权限</span>
            <span v-if="role.permissions?.length > 4" class="perm-chip more">+{{ role.permissions.length - 4 }}</span>
          </div>
          <div class="role-card-foot">
            <span><strong>{{ role.userCount }}</strong> 个用户</span>
            <div class="role-actions">
              <button class="btn-text" type="button" @click="openEdit(role)">编辑</button>
              <button v-if="!role.builtin" class="btn-text danger" type="button" @click="remove(role)">删除</button>
            </div>
          </div>
        </article>
      </section>
    </template>

    <div v-if="modalOpen" class="adm-modal-wrap" @click.self="modalOpen = false">
      <div class="adm-modal role-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">ROLE EDITOR</span><h3>{{ editingId ? '编辑角色' : '新建角色' }}</h3></div>
          <button class="btn-text" type="button" :disabled="saving" @click="modalOpen = false">关闭</button>
        </div>
        <div class="adm-modal-body">
          <div class="role-form-grid">
            <div class="field">
              <label>角色编码</label>
              <input v-model="form.code" :disabled="form.builtin" maxlength="32" placeholder="例如 EDITOR" @input="form.code = form.code.toUpperCase()" />
              <span class="meta">大写字母开头，可包含数字、下划线、连字符</span>
            </div>
            <div class="field">
              <label>角色名称</label>
              <input v-model="form.name" maxlength="64" placeholder="例如 编辑" />
            </div>
          </div>
          <div class="field">
            <label>角色描述</label>
            <textarea v-model="form.description" rows="2" maxlength="255" placeholder="这个角色能做什么"></textarea>
          </div>
          <label class="adm-check-line role-enable">
            <input v-model="form.enabled" type="checkbox" :disabled="form.code === 'ADMIN'" />
            启用这个角色
          </label>
          <div class="perm-editor">
            <div class="perm-editor-head">
              <strong>权限</strong>
              <span class="meta">勾选后台模块访问权限</span>
            </div>
            <div v-for="group in permissionGroups" :key="group.group" class="perm-editor-group">
              <span class="perm-editor-group-name">{{ group.group }}</span>
              <div class="perm-editor-items">
                <label v-for="p in group.items" :key="p.code" class="perm-check" :class="{ on: form.permissions.includes(p.code) }">
                  <input type="checkbox" :checked="form.permissions.includes(p.code)" :disabled="form.code === 'ADMIN'" @change="togglePermission(p.code)" />
                  <span>{{ p.label }}</span>
                  <code>{{ p.code }}</code>
                </label>
              </div>
            </div>
          </div>
        </div>
        <div class="adm-modal-actions split">
          <button class="btn btn-ghost btn-sm" type="button" :disabled="saving" @click="modalOpen = false">取消</button>
          <button class="btn btn-sm" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存角色' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.role-intro { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 22px 24px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); }
.role-intro h2 { margin: 6px 0; font-size: 21px; color: var(--ink); }
.role-intro p { margin: 0; color: var(--muted); font-size: 13px; }
.role-admin-hint { color: var(--muted); font-size: 12px; padding: 6px 10px; border-radius: 999px; background: var(--accent-soft); }
.perm-groups { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 18px; }
.perm-list { display: grid; gap: 2px; }
.perm-row { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px dashed var(--line); font-size: 12px; }
.perm-row:last-child { border-bottom: 0; }
.perm-row code { min-width: 160px; color: var(--accent-ink); font-size: 11px; }
.perm-row span { color: var(--ink-soft); }
.role-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.role-card { display: flex; flex-direction: column; padding: 18px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); }
.role-card.builtin { border-color: color-mix(in srgb, var(--accent) 35%, var(--line)); }
.role-card.disabled { opacity: .65; }
.role-card-top { display: flex; align-items: center; gap: 10px; }
.role-emblem { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 11px; background: var(--accent-soft); color: var(--accent-ink); font-weight: 800; }
.role-title { flex: 1; min-width: 0; }
.role-title h3 { margin: 0; font-size: 16px; color: var(--ink); }
.role-title code { color: var(--muted); font-size: 10px; }
.role-status { padding: 2px 7px; border-radius: 999px; background: var(--line); color: var(--muted); font-size: 10px; }
.role-status.on { background: rgba(5, 150, 105, .12); color: #047857; }
.role-desc { min-height: 44px; margin: 12px 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.role-perms { display: flex; flex-wrap: wrap; gap: 5px; min-height: 50px; align-content: flex-start; }
.perm-chip { padding: 3px 7px; border-radius: 999px; background: var(--accent-soft); color: var(--accent-ink); font-size: 10px; }
.perm-chip.empty { background: var(--line); color: var(--muted); }
.perm-chip.more { background: transparent; border: 1px dashed var(--line-strong); color: var(--muted); }
.role-card-foot { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--line); color: var(--muted); font-size: 11px; }
.role-card-foot strong { color: var(--ink); font-size: 14px; }
.role-actions { display: flex; gap: 8px; }
.btn-text.danger { color: #b91c1c; }
.role-modal { width: min(760px, calc(100vw - 32px)); max-height: 88vh; display: flex; flex-direction: column; }
.role-modal .adm-modal-body { overflow: auto; }
.role-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.role-enable { margin: 14px 0; }
.perm-editor { margin-top: 8px; border: 1px solid var(--line); border-radius: 10px; padding: 14px; }
.perm-editor-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 10px; }
.perm-editor-group { margin-top: 12px; }
.perm-editor-group-name { color: var(--muted); font-size: 11px; letter-spacing: .06em; text-transform: uppercase; }
.perm-editor-items { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; margin-top: 6px; }
.perm-check { display: flex; align-items: center; gap: 7px; padding: 7px 9px; border: 1px solid var(--line); border-radius: 7px; cursor: pointer; font-size: 12px; }
.perm-check.on { border-color: var(--accent); background: var(--accent-soft); }
.perm-check code { margin-left: auto; color: var(--muted); font-size: 9px; }
.perm-check input { accent-color: var(--accent); }
@media (max-width: 1000px) { .role-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) { .role-grid, .perm-groups { grid-template-columns: 1fr; } .role-form-grid, .perm-editor-items { grid-template-columns: 1fr; } }
</style>
