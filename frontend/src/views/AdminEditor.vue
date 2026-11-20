<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { toast } from '../state'
import MarkdownBody from '../components/MarkdownBody.vue'

const route = useRoute()
const router = useRouter()

const form = reactive({
  title: '',
  summary: '',
  content: '',
  cover: '',
  tags: '',
  status: 'PUBLISHED',
  top: false
})

const preview = ref(false)
const saving = ref(false)
const uploading = ref(false)
const loading = ref(false)
const coverInput = ref(null)

const isEdit = computed(() => Boolean(route.params.id))
const charCount = computed(() => form.content.replace(/\s/g, '').length)

async function load() {
  if (!isEdit.value) return
  loading.value = true
  try {
    const res = await http.get(`/posts/${route.params.id}`)
    const p = res.data.post
    Object.assign(form, {
      title: p.title,
      summary: p.summary || '',
      content: res.data.content || '',
      cover: p.cover || '',
      tags: p.tags || '',
      status: p.status,
      top: p.top
    })
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function save(status) {
  if (!form.title.trim()) {
    toast('标题不能为空', 'error')
    return
  }
  if (!form.content.trim()) {
    toast('正文不能为空', 'error')
    return
  }
  form.status = status
  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      summary: form.summary.trim(),
      content: form.content,
      cover: form.cover,
      tags: form.tags,
      status: form.status,
      top: form.top
    }
    if (isEdit.value) {
      await http.put(`/admin/posts/${route.params.id}`, payload)
    } else {
      await http.post('/admin/posts', payload)
    }
    toast(status === 'PUBLISHED' ? '文章已发布' : '草稿已保存')
    router.push('/admin')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

function pickCover() {
  coverInput.value?.click()
}

async function onCover(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  uploading.value = true
  try {
    const res = await http.post('/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    form.cover = res.data.url
    toast('封面已上传')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="editor-head">
      <div>
        <router-link to="/admin" class="back-link" style="margin-bottom: 8px">← 返回后台</router-link>
        <h1>{{ isEdit ? '编辑文章' : '写新文章' }}</h1>
      </div>
      <div style="display: flex; align-items: center; gap: 10px">
        <span class="meta">{{ charCount }} 字</span>
        <select v-model="form.status" class="tag" style="padding: 7px 10px; border-radius: 2px">
          <option value="PUBLISHED">已发布</option>
          <option value="DRAFT">草稿</option>
        </select>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="saving" @click="save('DRAFT')">存为草稿</button>
        <button class="btn btn-sm" type="button" :disabled="saving" @click="save('PUBLISHED')">
          {{ saving ? '保存中…' : '保存并发布' }}
        </button>
      </div>
    </div>

    <div class="editor-grid">
      <div class="editor-side">
        <div class="field">
          <label for="title">标题</label>
          <input id="title" v-model="form.title" type="text" maxlength="160" placeholder="给这篇文章起个名字" />
        </div>

        <div class="field">
          <label for="summary">摘要</label>
          <textarea id="summary" v-model="form.summary" style="min-height: 84px" placeholder="留空则自动截取正文开头"></textarea>
        </div>

        <div class="field">
          <label for="tags">标签</label>
          <input id="tags" v-model="form.tags" type="text" placeholder="用逗号分隔，如 Java,随笔" />
        </div>

        <div class="field">
          <label>封面</label>
          <img v-if="form.cover" class="cover-preview" :src="form.cover" alt="封面预览" />
          <div style="display: flex; gap: 8px">
            <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickCover">
              {{ uploading ? '上传中…' : form.cover ? '更换封面' : '上传封面' }}
            </button>
            <button v-if="form.cover" class="btn-text" type="button" @click="form.cover = ''">移除</button>
          </div>
          <input ref="coverInput" type="file" accept="image/*" style="display: none" @change="onCover" />
        </div>

        <div class="field">
          <label style="display: flex; align-items: center; gap: 8px; letter-spacing: 0">
            <input v-model="form.top" type="checkbox" style="width: auto" />
            置顶这篇文章
          </label>
        </div>

        <p class="meta" style="line-height: 1.8">
          正文支持标准 Markdown：标题、列表、代码块、引用、表格、图片。
        </p>
      </div>

      <div class="editor-main">
        <div class="editor-tabs">
          <button type="button" :class="{ on: !preview }" @click="preview = false">编写</button>
          <button type="button" :class="{ on: preview }" @click="preview = true">预览</button>
        </div>

        <div v-if="loading" class="empty">加载中…</div>
        <textarea
          v-else-if="!preview"
          v-model="form.content"
          class="editor-textarea"
          placeholder="# 标题&#10;&#10;从这里开始写……"
          spellcheck="false"
        ></textarea>
        <div v-else class="editor-preview">
          <MarkdownBody v-if="form.content.trim()" :content="form.content" />
          <div v-else class="empty">还没有内容可预览</div>
        </div>
      </div>
    </div>
  </div>
</template>
