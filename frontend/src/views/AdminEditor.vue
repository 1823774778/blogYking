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
  top: false,
  reviewNote: ''
})

const preview = ref(false)
const saving = ref(false)
const uploading = ref(false)
const generating = ref(false)
const loading = ref(false)
const coverInput = ref(null)
const aiGenOpen = ref(false)
const aiGenLoading = ref(false)
const aiReviewOpen = ref(false)
const aiReviewLoading = ref(false)
const aiReview = ref(null)
const aiGen = reactive({ topic: '', outline: '', tone: 'natural', length: 'medium' })

const isEdit = computed(() => Boolean(route.params.id))
const charCount = computed(() => form.content.replace(/\s/g, '').length)
const statusText = computed(() => ({
  PUBLISHED: '已发布',
  PENDING: '待审核',
  DRAFT: '草稿',
  REJECTED: '已退回'
}[form.status] || form.status))

async function load() {
  if (!isEdit.value) return
  loading.value = true
  try {
    const res = await http.get(`/admin/posts/${route.params.id}`)
    const p = res.data.post
    Object.assign(form, {
      title: p.title,
      summary: p.summary || '',
      content: res.data.content || '',
      cover: p.cover || '',
      tags: p.tags || '',
      status: p.status,
      top: p.top,
      reviewNote: p.reviewNote || ''
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
    toast(status === 'PUBLISHED' ? '文章已发布' : status === 'PENDING' ? '文章已提交审核' : '草稿已保存')
    router.push({ path: '/admin/posts', query: { tab: status === 'PUBLISHED' ? 'published' : status === 'PENDING' ? 'pending' : 'draft' } })
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function generateSummary() {
  if (!form.content.trim()) {
    toast('先写正文，再生成摘要', 'error')
    return
  }
  generating.value = true
  try {
    const res = await http.post('/admin/ai/summarize', {
      title: form.title,
      content: form.content
    }, { timeout: 60000 })
    form.summary = res.data.summary
    toast('AI 摘要已生成，可继续修改')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    generating.value = false
  }
}

async function generateArticle() {
  if (!aiGen.topic.trim()) {
    toast('请先填写文章主题', 'error')
    return
  }
  aiGenLoading.value = true
  try {
    const res = await http.post('/admin/ai/generate-article', {
      topic: aiGen.topic.trim(),
      outline: aiGen.outline.trim(),
      tone: aiGen.tone,
      length: aiGen.length
    }, { timeout: 120000 })
    const data = res.data || {}
    if (data.title) form.title = data.title
    if (data.summary) form.summary = data.summary
    if (data.tags) form.tags = data.tags
    if (data.content) form.content = data.content
    aiGenOpen.value = false
    toast('AI 草稿已生成，请检查后继续编辑')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    aiGenLoading.value = false
  }
}

async function reviewArticle() {
  if (!form.content.trim()) {
    toast('正文为空，无法审核', 'error')
    return
  }
  aiReviewLoading.value = true
  aiReview.value = null
  try {
    aiReview.value = (await http.post('/admin/ai/review-article', {
      title: form.title,
      content: form.content
    }, { timeout: 90000 })).data
    aiReviewOpen.value = true
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    aiReviewLoading.value = false
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
  <div class="adm-page adm-editor-page">
    <div class="adm-editor-toolbar">
      <router-link to="/admin/posts" class="back-link">← 返回文章管理</router-link>
      <div class="adm-editor-actions">
        <span class="adm-status" :class="`is-${String(form.status).toLowerCase()}`">{{ statusText }}</span>
        <span class="meta">{{ charCount }} 字</span>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="aiGenLoading || saving" @click="aiGenOpen = true">AI 生成文章</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="aiReviewLoading || saving" @click="reviewArticle">{{ aiReviewLoading ? '审核中…' : 'AI 审核文章' }}</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="saving" @click="save('DRAFT')">存为草稿</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="saving" @click="save('PENDING')">提交审核</button>
        <button class="btn btn-sm" type="button" :disabled="saving" @click="save('PUBLISHED')">{{ saving ? '保存中…' : '保存并发布' }}</button>
      </div>
    </div>

    <div v-if="form.reviewNote && form.status === 'REJECTED'" class="adm-review-banner">
      <strong>这篇文章被退回了</strong>
      <p>{{ form.reviewNote }}</p>
      <span>修改后可以重新提交审核，或在确认无误后直接发布。</span>
    </div>

    <div v-if="loading" class="adm-loading">正在加载文章…</div>

    <div v-else class="editor-grid adm-editor-grid">
      <div class="editor-side adm-card">
        <div class="field">
          <label for="title">标题</label>
          <input id="title" v-model="form.title" type="text" maxlength="160" placeholder="给这篇文章起个名字" />
        </div>

        <div class="field">
          <label for="summary">摘要</label>
          <textarea id="summary" v-model="form.summary" maxlength="200" style="min-height: 92px" placeholder="留空则自动截取正文开头"></textarea>
          <div class="adm-summary-action">
            <button class="btn btn-ghost btn-sm" type="button" :disabled="generating" @click="generateSummary">{{ generating ? '生成中…' : 'AI 生成摘要' }}</button>
            <span class="meta">需在站点设置中启用 AI</span>
          </div>
        </div>

        <div class="field">
          <label for="tags">标签</label>
          <input id="tags" v-model="form.tags" type="text" placeholder="用逗号分隔，如 Java,随笔" />
        </div>

        <div class="field">
          <label>封面</label>
          <img v-if="form.cover" class="cover-preview" :src="form.cover" alt="封面预览" />
          <div style="display: flex; gap: 8px; flex-wrap: wrap">
            <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickCover">{{ uploading ? '上传中…' : form.cover ? '更换封面' : '上传封面' }}</button>
            <button v-if="form.cover" class="btn-text" type="button" @click="form.cover = ''">移除</button>
          </div>
          <input ref="coverInput" type="file" accept="image/*" hidden @change="onCover" />
        </div>

        <div class="field">
          <label class="adm-check-line">
            <input v-model="form.top" type="checkbox" />
            置顶这篇文章
          </label>
        </div>

        <p class="meta adm-note">正文支持标题、列表、代码块、引用、表格和图片。发布前建议点“预览”检查排版。</p>
      </div>

      <div class="editor-main adm-card">
        <div class="editor-tabs">
          <button type="button" :class="{ on: !preview }" @click="preview = false">编写</button>
          <button type="button" :class="{ on: preview }" @click="preview = true">预览</button>
        </div>

        <textarea
          v-if="!preview"
          v-model="form.content"
          class="editor-textarea"
          placeholder="# 标题&#10;&#10;从这里开始写……"
          spellcheck="false"
        ></textarea>
        <div v-else class="editor-preview">
          <MarkdownBody v-if="form.content.trim()" :content="form.content" />
          <div v-else class="adm-empty">还没有内容可预览</div>
        </div>
      </div>
    </div>

    <div v-if="aiGenOpen" class="adm-modal-wrap" @click.self="aiGenOpen = false">
      <div class="adm-modal is-wide">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI WRITER</span><h2>AI 生成文章</h2><p class="meta">生成后会覆盖当前标题、摘要、标签和正文，请在生成前保存已有草稿。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="aiGenOpen = false">×</button>
        </div>
        <div class="form-grid">
          <div class="field span-2">
            <label for="aiTopic">文章主题</label>
            <input id="aiTopic" v-model="aiGen.topic" type="text" placeholder="例如：用 Spring Boot 做一个可热更新的个人博客" />
          </div>
          <div class="field span-2">
            <label for="aiOutline">提纲 / 补充要求（可选）</label>
            <textarea id="aiOutline" v-model="aiGen.outline" style="min-height: 110px" placeholder="想覆盖的小标题、代码示例、语气或需要避免的内容"></textarea>
          </div>
          <div class="field">
            <label for="aiTone">写作风格</label>
            <select id="aiTone" v-model="aiGen.tone">
              <option value="natural">自然清晰</option>
              <option value="professional">专业克制</option>
              <option value="casual">轻松口语</option>
              <option value="story">叙事随笔</option>
            </select>
          </div>
          <div class="field">
            <label for="aiLength">篇幅</label>
            <select id="aiLength" v-model="aiGen.length">
              <option value="short">短篇 600–900 字</option>
              <option value="medium">中篇 1200–1800 字</option>
              <option value="long">长篇 2000–3000 字</option>
            </select>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="aiGenLoading" @click="aiGenOpen = false">取消</button>
          <button class="btn" type="button" :disabled="aiGenLoading" @click="generateArticle">{{ aiGenLoading ? '生成中…' : '开始生成' }}</button>
        </div>
      </div>
    </div>

    <div v-if="aiReviewOpen" class="adm-modal-wrap" @click.self="aiReviewOpen = false">
      <div class="adm-modal is-wide">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI REVIEW</span><h2>AI 审核结果</h2><p class="meta">审核意见仅供参考，最终发布决定仍由你确认。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="aiReviewOpen = false">×</button>
        </div>
        <div v-if="aiReview" class="ai-review-result">
          <div class="ai-review-score"><strong>{{ aiReview.score }}</strong><span>/ 100</span><em>{{ aiReview.verdict }}</em></div>
          <p class="ai-review-conclusion">{{ aiReview.conclusion }}</p>
          <div v-if="aiReview.issues?.length" class="ai-review-block">
            <h3>发现的问题</h3>
            <ul><li v-for="(item, i) in aiReview.issues" :key="i">{{ item }}</li></ul>
          </div>
          <div v-if="aiReview.suggestions?.length" class="ai-review-block">
            <h3>修改建议</h3>
            <ul><li v-for="(item, i) in aiReview.suggestions" :key="i">{{ item }}</li></ul>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="aiReviewOpen = false">关闭</button>
          <button class="btn" type="button" @click="aiReviewOpen = false">继续编辑</button>
        </div>
      </div>
    </div>
  </div>
</template>
