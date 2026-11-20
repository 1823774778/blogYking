<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import http from '../../api'
import { toast } from '../../state'
import { formatDateTime } from '../../utils'

const tags = ref([])
const loading = ref(false)
const modalOpen = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = reactive({
  name: '',
  slug: '',
  description: '',
  color: '#2f9d79',
  sortOrder: 0,
  visible: true
})

const total = computed(() => tags.value.length)
const visibleTotal = computed(() => tags.value.filter((t) => t.visible).length)

async function load() {
  loading.value = true
  try {
    tags.value = (await http.get('/admin/tags')).data || []
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', slug: '', description: '', color: '#2f9d79', sortOrder: 0, visible: true })
  modalOpen.value = true
}

function openEdit(tag) {
  editingId.value = tag.id
  Object.assign(form, {
    name: tag.name,
    slug: tag.slug || '',
    description: tag.description || '',
    color: tag.color || '#2f9d79',
    sortOrder: tag.sortOrder || 0,
    visible: tag.visible
  })
  modalOpen.value = true
}

async function save() {
  if (!form.name.trim()) {
    toast('请填写标签名称', 'error')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      slug: form.slug.trim(),
      description: form.description.trim(),
      color: form.color,
      sortOrder: Number(form.sortOrder) || 0,
      visible: form.visible
    }
    if (editingId.value) {
      await http.put(`/admin/tags/${editingId.value}`, payload)
      toast('标签已更新，文章内同名标签已同步')
    } else {
      await http.post('/admin/tags', payload)
      toast('标签已创建')
    }
    modalOpen.value = false
    load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function toggleVisible(tag) {
  try {
    await http.put(`/admin/tags/${tag.id}`, {
      name: tag.name,
      slug: tag.slug,
      description: tag.description,
      color: tag.color,
      sortOrder: tag.sortOrder,
      visible: !tag.visible
    })
    tag.visible = !tag.visible
    toast(tag.visible ? '标签已在前台显示' : '标签已从前台隐藏')
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function remove(tag) {
  if (!window.confirm(`确定删除标签“${tag.name}”吗？它会从所有文章的标签中一并移除。`)) return
  try {
    await http.delete(`/admin/tags/${tag.id}`)
    toast('标签已删除')
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
        <span class="adm-total-pill">标签目录 · {{ total }}</span>
        <span class="meta">{{ visibleTotal }} 个前台可见</span>
      </div>
      <div class="adm-head-actions">
        <button class="btn btn-sm" type="button" @click="openCreate">新增标签</button>
      </div>
    </div>

    <section class="adm-card adm-callout">
      <div>
        <span class="adm-card-kicker">TAGS</span>
        <h2>标签管理</h2>
        <p>标签目录存储在数据库，文章保存时会自动把新标签同步进来。改名会同步替换所有文章中的同名标签，删除会从所有文章中移除。</p>
      </div>
    </section>

    <div v-if="loading" class="adm-loading">正在读取标签…</div>

    <section v-else class="adm-card adm-table-card">
      <div class="adm-table-wrap">
        <table class="table adm-table">
          <thead>
            <tr>
              <th>标签</th>
              <th>文章数</th>
              <th>Slug</th>
              <th>排序</th>
              <th>前台</th>
              <th>更新时间</th>
              <th class="align-right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tag in tags" :key="tag.id">
              <td>
                <div class="tag-admin-name">
                  <i :style="{ background: tag.color || '#2f9d79' }"></i>
                  <div><strong>{{ tag.name }}</strong><span v-if="tag.description">{{ tag.description }}</span></div>
                </div>
              </td>
              <td><span class="badge" :class="{ on: tag.count > 0 }">{{ tag.count }}</span></td>
              <td><code>{{ tag.slug || '—' }}</code></td>
              <td>{{ tag.sortOrder }}</td>
              <td>
                <button class="badge" :class="tag.visible ? 'on' : 'off'" type="button" @click="toggleVisible(tag)">{{ tag.visible ? '显示' : '隐藏' }}</button>
              </td>
              <td class="meta">{{ formatDateTime(tag.updatedAt) }}</td>
              <td class="align-right">
                <div class="adm-row-actions">
                  <button class="btn-text" type="button" @click="openEdit(tag)">编辑</button>
                  <button class="btn-text danger" type="button" @click="remove(tag)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!tags.length">
              <td colspan="7"><div class="adm-empty">还没有标签。新增标签，或在文章里填写标签后自动同步。</div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="modalOpen" class="adm-modal-wrap" @click.self="modalOpen = false">
      <div class="adm-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">TAG</span><h2>{{ editingId ? '编辑标签' : '新增标签' }}</h2><p class="meta">标签名称会出现在前台标签云和文章详情。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="modalOpen = false">×</button>
        </div>
        <div class="form-grid">
          <div class="field span-2">
            <label for="tagName">标签名称</label>
            <input id="tagName" v-model="form.name" type="text" maxlength="40" placeholder="例如：Spring Boot" />
          </div>
          <div class="field">
            <label for="tagColor">颜色</label>
            <div class="tag-color-input">
              <input id="tagColor" v-model="form.color" type="color" />
              <input v-model="form.color" type="text" maxlength="7" />
            </div>
          </div>
          <div class="field">
            <label for="tagSort">排序</label>
            <input id="tagSort" v-model.number="form.sortOrder" type="number" />
          </div>
          <div class="field span-2">
            <label for="tagSlug">Slug（可选）</label>
            <input id="tagSlug" v-model="form.slug" type="text" maxlength="60" placeholder="留空自动生成" />
          </div>
          <div class="field span-2">
            <label for="tagDesc">描述（可选）</label>
            <textarea id="tagDesc" v-model="form.description" maxlength="512" style="min-height: 80px"></textarea>
          </div>
          <div class="field span-2">
            <label class="adm-switch-label">
              <span>在前台显示</span>
              <input v-model="form.visible" type="checkbox" />
              <i class="adm-switch" aria-hidden="true"></i>
            </label>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="saving" @click="modalOpen = false">取消</button>
          <button class="btn" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存标签' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
