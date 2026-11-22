<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
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

const sourceRef = ref(null)
const previewRef = ref(null)
const settingsOpen = ref(false)
const syncScroll = ref(false)
let syncing = false
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
const aiGen = reactive({ topic: '', outline: '', tone: 'natural', length: 'medium', prompt: '' })
const aiReviewPromptOpen = ref(false)
const aiReviewPrompt = ref('')
const summaryPromptOpen = ref(false)
const summaryPrompt = ref('')
const aiConfig = reactive({
  articleEnabled: true,
  summaryEnabled: true,
  articleGeneratePrompt: '',
  articleReviewPrompt: '',
  summaryPrompt: ''
})

const isEdit = computed(() => Boolean(route.params.id))
const statusLabel = computed(() => (form.status === 'PUBLISHED' ? '已发布' : form.status === 'DRAFT' ? '草稿' : '待审核'))
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

async function loadAiConfig() {
  try {
    const cfg = (await http.get('/admin/config')).data || {}
    aiConfig.articleEnabled = String(cfg.aiArticleEnabled ?? 'true') === 'true'
    aiConfig.summaryEnabled = String(cfg.aiSummaryEnabled ?? 'true') === 'true'
    aiConfig.articleGeneratePrompt = cfg.aiArticleGeneratePrompt || ''
    aiConfig.articleReviewPrompt = cfg.aiArticleReviewPrompt || ''
    aiConfig.summaryPrompt = cfg.aiSummaryPrompt || ''
    aiGen.prompt = aiConfig.articleGeneratePrompt
    aiReviewPrompt.value = aiConfig.articleReviewPrompt
    summaryPrompt.value = aiConfig.summaryPrompt
  } catch {
    /* 配置读取失败时由后端功能开关兜底 */
  }
}

function openSummaryPrompt() {
  if (!aiConfig.summaryEnabled) {
    toast('AI 摘要功能未开启，请先在站点设置中打开', 'error')
    return
  }
  if (!form.content.trim()) {
    toast('先写正文，再生成摘要', 'error')
    return
  }
  summaryPrompt.value = summaryPrompt.value || aiConfig.summaryPrompt
  summaryPromptOpen.value = true
}

async function generateSummary() {
  if (!form.content.trim()) {
    toast('先写正文，再生成摘要', 'error')
    return
  }
  if (!summaryPrompt.value.trim()) {
    toast('请填写 AI 摘要提示词', 'error')
    return
  }
  generating.value = true
  try {
    const res = await http.post('/admin/ai/summarize', {
      title: form.title,
      content: form.content,
      prompt: summaryPrompt.value.trim()
    }, { timeout: 60000 })
    form.summary = res.data.summary
    summaryPromptOpen.value = false
    toast('AI 摘要已生成，可继续修改')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    generating.value = false
  }
}

async function generateArticle() {
  if (!aiConfig.articleEnabled) {
    toast('AI 文章功能未开启，请先在站点设置中打开', 'error')
    return
  }
  if (!aiGen.topic.trim()) {
    toast('请先填写文章主题', 'error')
    return
  }
  if (!aiGen.prompt.trim()) {
    toast('请填写 AI 生成文章提示词', 'error')
    return
  }
  aiGenLoading.value = true
  try {
    const res = await http.post('/admin/ai/generate-article', {
      topic: aiGen.topic.trim(),
      outline: aiGen.outline.trim(),
      tone: aiGen.tone,
      length: aiGen.length,
      prompt: aiGen.prompt.trim()
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

function openReviewPrompt() {
  if (!aiConfig.articleEnabled) {
    toast('AI 文章功能未开启，请先在站点设置中打开', 'error')
    return
  }
  if (!form.content.trim()) {
    toast('正文为空，无法审核', 'error')
    return
  }
  aiReviewPrompt.value = aiReviewPrompt.value || aiConfig.articleReviewPrompt
  aiReviewPromptOpen.value = true
}

async function reviewArticle() {
  if (!aiReviewPrompt.value.trim()) {
    toast('请填写 AI 审核文章提示词', 'error')
    return
  }
  aiReviewLoading.value = true
  aiReview.value = null
  try {
    aiReview.value = (await http.post('/admin/ai/review-article', {
      title: form.title,
      content: form.content,
      prompt: aiReviewPrompt.value.trim()
    }, { timeout: 90000 })).data
    aiReviewPromptOpen.value = false
    aiReviewOpen.value = true
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    aiReviewLoading.value = false
  }
}

function goBack() {
  router.push('/admin/posts')
}

function toggleSync() {
  syncScroll.value = !syncScroll.value
}

function syncFromSource() {
  if (!syncScroll.value || syncing) return
  const a = sourceRef.value
  const b = previewRef.value
  if (!a || !b) return
  const maxA = a.scrollHeight - a.clientHeight
  const maxB = b.scrollHeight - b.clientHeight
  if (maxA <= 0 || maxB <= 0) return
  syncing = true
  b.scrollTop = (a.scrollTop / maxA) * maxB
  requestAnimationFrame(() => { syncing = false })
}

function syncFromPreview() {
  if (!syncScroll.value || syncing) return
  const a = sourceRef.value
  const b = previewRef.value
  if (!a || !b) return
  const maxA = a.scrollHeight - a.clientHeight
  const maxB = b.scrollHeight - b.clientHeight
  if (maxA <= 0 || maxB <= 0) return
  syncing = true
  a.scrollTop = (b.scrollTop / maxB) * maxA
  requestAnimationFrame(() => { syncing = false })
}

function insertMarkdown(before, after = '', placeholder = '') {
  const el = sourceRef.value
  if (!el) return
  const start = el.selectionStart
  const end = el.selectionEnd
  const selected = form.content.slice(start, end) || placeholder
  form.content = form.content.slice(0, start) + before + selected + after + form.content.slice(end)
  nextTick(() => {
    el.focus()
    el.selectionStart = start + before.length
    el.selectionEnd = start + before.length + selected.length
  })
}

function insertBlock(text) {
  const el = sourceRef.value
  if (!el) return
  const start = el.selectionStart
  const needsBreak = start > 0 && !form.content.slice(0, start).endsWith('\n')
  const block = (needsBreak ? '\n\n' : '') + text + '\n'
  form.content = form.content.slice(0, start) + block + form.content.slice(el.selectionEnd)
  nextTick(() => {
    el.focus()
    el.selectionStart = el.selectionEnd = start + block.length
  })
}

function insertCodeBlock() {
  insertBlock('```js\n// 在这里写代码\n```')
}

function insertTable() {
  insertBlock('| 列 1 | 列 2 |\n| --- | --- |\n| 内容 | 内容 |')
}

function downloadMarkdown() {
  const name = (form.title.trim() || '未命名文章').replace(/[\\/:*?"<>|]/g, '-')
  const blob = new Blob([form.content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${name}.md`
  a.click()
  URL.revokeObjectURL(url)
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

onMounted(() => {
  load()
  loadAiConfig()
})
</script>

<template>
  <div class="adm-editor-full" @keydown.ctrl.s.prevent="save(form.status)" @keydown.meta.s.prevent="save(form.status)">
    <header class="fe-topbar">
      <div class="fe-title-wrap">
        <span class="fe-mark" aria-hidden="true">墨</span>
        <input
          v-model="form.title"
          class="fe-title"
          type="text"
          maxlength="160"
          placeholder="请输入文章标题"
          aria-label="文章标题"
        />
      </div>
      <div class="fe-top-actions">
        <button class="fe-sync" :class="{ on: syncScroll }" type="button" :aria-pressed="syncScroll" @click="toggleSync">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M8 7h11m0 0-3-3m3 3-3 3M16 17H5m0 0 3 3m-3-3 3-3" />
          </svg>
          同步滚动{{ syncScroll ? '开' : '关' }}
        </button>
        <span class="fe-status-pill" :class="`is-${String(form.status).toLowerCase()}`">{{ statusLabel }}</span>
        <button class="fe-btn fe-btn-ghost" type="button" :disabled="saving" @click="goBack">取消</button>
        <button class="fe-btn fe-btn-ghost fe-hide-sm" type="button" :disabled="saving" @click="save('DRAFT')">存草稿</button>
        <button class="fe-btn fe-btn-primary" type="button" :disabled="saving" @click="save(form.status)">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </header>

    <nav class="fe-toolbar" aria-label="Markdown 工具栏">
      <button class="fe-tool fe-tool-strong" type="button" title="加粗" @click="insertMarkdown('**', '**', '粗体文字')">B</button>
      <button class="fe-tool fe-tool-italic" type="button" title="斜体" @click="insertMarkdown('*', '*', '斜体文字')">I</button>
      <button class="fe-tool" type="button" title="二级标题" @click="insertBlock('## 小标题')">H</button>
      <span class="fe-tool-divider"></span>
      <button class="fe-tool" type="button" title="插入链接" @click="insertMarkdown('[', '](https://)', '链接文字')">↗</button>
      <button class="fe-tool" type="button" title="插入图片" @click="insertMarkdown('![', '](https://)', '图片描述')">▧</button>
      <button class="fe-tool" type="button" title="插入表格" @click="insertTable">▦</button>
      <span class="fe-tool-divider"></span>
      <button class="fe-tool" type="button" title="无序列表" @click="insertBlock('- 列表项\n- 列表项')">☰</button>
      <button class="fe-tool" type="button" title="有序列表" @click="insertBlock('1. 列表项\n2. 列表项')">1.</button>
      <button class="fe-tool" type="button" title="引用" @click="insertBlock('> 引用内容')">❝</button>
      <button class="fe-tool fe-tool-code" type="button" title="代码块" @click="insertCodeBlock">&lt;/&gt;</button>
      <button class="fe-tool" type="button" title="分隔线" @click="insertBlock('---')">—</button>
      <span class="fe-tool-divider"></span>
      <button class="fe-tool fe-tool-ai" type="button" title="AI 生成文章" :disabled="!aiConfig.articleEnabled" @click="aiGenOpen = true">✦ 生成</button>
      <button class="fe-tool" type="button" title="AI 审核文章" :disabled="!aiConfig.articleEnabled" @click="openReviewPrompt">✦ 审核</button>
      <button class="fe-tool" type="button" title="AI 生成摘要" :disabled="!aiConfig.summaryEnabled" @click="openSummaryPrompt">✦ 摘要</button>
      <span class="fe-tool-spacer"></span>
      <span class="fe-char-count">{{ charCount }} 字</span>
      <button class="fe-tool" type="button" title="下载 Markdown" @click="downloadMarkdown">⤓</button>
      <button class="fe-tool" type="button" title="文章设置" @click="settingsOpen = true">⚙</button>
    </nav>

    <div v-if="loading" class="fe-loading">正在加载文章…</div>

    <main v-else class="fe-body">
      <section class="fe-pane fe-source">
        <div class="fe-pane-label">MARKDOWN</div>
        <textarea
          ref="sourceRef"
          v-model="form.content"
          class="fe-textarea"
          placeholder="从这里开始写 Markdown……"
          spellcheck="false"
          @scroll.passive="syncFromSource"
        ></textarea>
      </section>
      <section class="fe-pane fe-preview" @scroll.passive="syncFromPreview">
        <div class="fe-pane-label">PREVIEW</div>
        <div ref="previewRef" class="fe-preview-inner">
          <MarkdownBody v-if="form.content.trim()" :content="form.content" />
          <div v-else class="fe-empty">左侧开始书写，这里会实时预览排版</div>
        </div>
      </section>
    </main>

    <footer class="fe-footer">
      <span>Markdown · GFM</span>
      <span v-if="form.reviewNote && form.status === 'REJECTED'" class="fe-reject">退回意见：{{ form.reviewNote }}</span>
      <span class="fe-footer-right">Ctrl / ⌘ + S 保存</span>
    </footer>

    <div v-if="settingsOpen" class="fe-drawer-mask" @click.self="settingsOpen = false">
      <aside class="fe-drawer" aria-label="文章设置">
        <header class="fe-drawer-head">
          <div>
            <span class="fe-drawer-kicker">ARTICLE META</span>
            <h2>文章设置</h2>
          </div>
          <button class="fe-drawer-close" type="button" aria-label="关闭" @click="settingsOpen = false">×</button>
        </header>
        <div class="fe-drawer-body">
          <div class="field">
            <label for="summary">摘要</label>
            <textarea id="summary" v-model="form.summary" maxlength="200" style="min-height: 110px" placeholder="留空则自动截取正文开头"></textarea>
            <div class="adm-summary-action">
              <button class="btn btn-ghost btn-sm" type="button" :disabled="generating || !aiConfig.summaryEnabled" @click="openSummaryPrompt">{{ generating ? '生成中…' : 'AI 生成摘要' }}</button>
              <span class="meta">{{ aiConfig.summaryEnabled ? '点击后可编辑本次提示词' : 'AI 摘要已在站点设置中关闭' }}</span>
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
            <label for="statusSelect">文章状态</label>
            <select id="statusSelect" v-model="form.status">
              <option value="DRAFT">草稿</option>
              <option value="PENDING">待审核</option>
              <option value="PUBLISHED">已发布</option>
            </select>
          </div>
          <div class="field">
            <label class="adm-check-line">
              <input v-model="form.top" type="checkbox" />
              置顶这篇文章
            </label>
          </div>
          <p class="meta adm-note">正文支持标题、列表、代码块、引用、表格和图片。发布前可在右侧预览检查排版。</p>
        </div>
        <footer class="fe-drawer-foot">
          <button class="btn btn-ghost" type="button" @click="settingsOpen = false">完成</button>
          <button class="btn" type="button" :disabled="saving" @click="save(form.status)">保存</button>
        </footer>
      </aside>
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
          <div class="field span-2">
            <label for="aiGenPrompt">生成提示词</label>
            <textarea id="aiGenPrompt" v-model="aiGen.prompt" maxlength="4000" style="min-height: 96px" placeholder="描述写作角色、文章结构、语气和必须遵守的规则"></textarea>
            <p class="adm-note">默认读取站点设置中的 AI 文章提示词，本次修改只影响这次生成。</p>
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

    <div v-if="summaryPromptOpen" class="adm-modal-wrap" @click.self="summaryPromptOpen = false">
      <div class="adm-modal is-wide">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI SUMMARY</span><h2>AI 生成摘要</h2><p class="meta">默认读取站点设置中的 AI 摘要提示词，本次修改只影响这次生成。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="summaryPromptOpen = false">×</button>
        </div>
        <div class="field">
          <label for="summaryPrompt">提示词</label>
          <textarea id="summaryPrompt" v-model="summaryPrompt" maxlength="4000" style="min-height: 150px"></textarea>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="generating" @click="summaryPromptOpen = false">取消</button>
          <button class="btn" type="button" :disabled="generating" @click="generateSummary">{{ generating ? '生成中…' : '生成摘要' }}</button>
        </div>
      </div>
    </div>

    <div v-if="aiReviewPromptOpen" class="adm-modal-wrap" @click.self="aiReviewPromptOpen = false">
      <div class="adm-modal is-wide">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI REVIEW</span><h2>AI 审核文章</h2><p class="meta">默认读取站点设置中的审核提示词，本次修改只影响这次审核。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="aiReviewPromptOpen = false">×</button>
        </div>
        <div class="field">
          <label for="articleReviewPrompt">审核提示词</label>
          <textarea id="articleReviewPrompt" v-model="aiReviewPrompt" maxlength="4000" style="min-height: 150px"></textarea>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="aiReviewLoading" @click="aiReviewPromptOpen = false">取消</button>
          <button class="btn" type="button" :disabled="aiReviewLoading" @click="reviewArticle">{{ aiReviewLoading ? '审核中…' : '开始审核' }}</button>
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

<style scoped>
.adm-editor-full {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #0f172a;
  color: #e2e8f0;
  font-family: var(--sans, "Inter", "PingFang SC", "Microsoft YaHei", sans-serif);
}
.fe-topbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 58px;
  padding: 0 18px;
  background: #0b1220;
  border-bottom: 1px solid #243244;
}
.fe-title-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1 1 auto;
  min-width: 0;
}
.fe-mark {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: #1d4ed8;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.fe-title {
  flex: 1 1 auto;
  min-width: 0;
  height: 40px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: #162033;
  color: #f1f5f9;
  font-size: 17px;
  font-weight: 600;
  outline: none;
  transition: border-color .16s ease, background .16s ease;
}
.fe-title::placeholder { color: #64748b; font-weight: 500; }
.fe-title:focus { border-color: #3b82f6; background: #17233a; }
.fe-top-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}
.fe-sync {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 9px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #94a3b8;
  font-size: 12px;
  cursor: pointer;
}
.fe-sync svg { width: 15px; height: 15px; }
.fe-sync:hover, .fe-sync.on { color: #dbeafe; background: #1a2740; }
.fe-status-pill {
  padding: 3px 8px;
  border-radius: 999px;
  background: #1e293b;
  color: #94a3b8;
  font-size: 11px;
  letter-spacing: .02em;
}
.fe-status-pill.is-published { background: rgba(34, 197, 94, .14); color: #86efac; }
.fe-status-pill.is-draft { background: rgba(148, 163, 184, .14); color: #cbd5e1; }
.fe-status-pill.is-pending { background: rgba(245, 158, 11, .15); color: #fcd34d; }
.fe-btn {
  height: 34px;
  padding: 0 15px;
  border: 1px solid transparent;
  border-radius: 7px;
  font-size: 13px;
  cursor: pointer;
  transition: background .16s ease, border-color .16s ease;
}
.fe-btn:disabled { opacity: .55; cursor: default; }
.fe-btn-ghost { background: transparent; border-color: #2c3b52; color: #cbd5e1; }
.fe-btn-ghost:hover:not(:disabled) { background: #172033; border-color: #3b4b64; }
.fe-btn-primary { background: #2563eb; color: #fff; font-weight: 600; }
.fe-btn-primary:hover:not(:disabled) { background: #1d4ed8; }
.fe-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 2px;
  min-height: 46px;
  padding: 0 10px;
  overflow-x: auto;
  background: #101a2b;
  border-bottom: 1px solid #243244;
  scrollbar-width: none;
}
.fe-toolbar::-webkit-scrollbar { display: none; }
.fe-tool {
  flex: 0 0 auto;
  min-width: 33px;
  height: 32px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #93a4bb;
  font-size: 13px;
  cursor: pointer;
}
.fe-tool:hover:not(:disabled) { background: #1b2940; color: #e2e8f0; }
.fe-tool:disabled { opacity: .4; cursor: default; }
.fe-tool-strong { font-weight: 800; color: #cbd5e1; }
.fe-tool-italic { font-style: italic; font-family: Georgia, serif; color: #cbd5e1; }
.fe-tool-code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.fe-tool-ai { color: #93c5fd; }
.fe-tool-divider { flex: 0 0 auto; width: 1px; height: 20px; margin: 0 5px; background: #28364c; }
.fe-tool-spacer { flex: 1 1 auto; min-width: 8px; }
.fe-char-count { flex: 0 0 auto; color: #64748b; font-size: 11px; padding: 0 6px; }
.fe-body {
  flex: 1 1 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-height: 0;
}
.fe-pane { position: relative; min-width: 0; min-height: 0; }
.fe-source { border-right: 1px solid #243244; }
.fe-pane-label {
  position: absolute;
  top: 10px;
  right: 14px;
  z-index: 2;
  color: #475569;
  font-size: 9.5px;
  letter-spacing: .12em;
  pointer-events: none;
}
.fe-textarea {
  display: block;
  width: 100%;
  height: 100%;
  padding: 30px 34px 42px;
  border: 0;
  outline: none;
  resize: none;
  background: #0f172a;
  color: #dbe7f5;
  font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
  font-size: 14px;
  line-height: 1.85;
  tab-size: 2;
}
.fe-textarea::placeholder { color: #4b5c73; }
.fe-preview {
  overflow: auto;
  background: #111827;
}
.fe-preview-inner {
  min-height: 100%;
  padding: 30px 38px 56px;
}
.fe-empty {
  margin-top: 20vh;
  color: #52627a;
  font-size: 13px;
  text-align: center;
}
.fe-preview :deep(.markdown) { color: #cbd5e1; font-size: 15px; line-height: 1.9; }
.fe-preview :deep(.markdown h1),
.fe-preview :deep(.markdown h2),
.fe-preview :deep(.markdown h3),
.fe-preview :deep(.markdown h4) { color: #f1f5f9; }
.fe-preview :deep(.markdown a) { color: #7dd3fc; }
.fe-preview :deep(.markdown code) { background: #1e293b; color: #fca5a5; border-radius: 4px; }
.fe-preview :deep(.markdown pre) { background: #0b1220; border-color: #243244; }
.fe-preview :deep(.markdown pre code) { color: #cbd5e1; }
.fe-preview :deep(.markdown blockquote) { border-left-color: #3b82f6; background: #162033; color: #94a3b8; }
.fe-preview :deep(.markdown table th) { background: #1a2740; color: #e2e8f0; border-color: #2c3b52; }
.fe-preview :deep(.markdown table td) { border-color: #2c3b52; }
.fe-preview :deep(.markdown hr) { border-color: #243244; }
.fe-footer {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 18px;
  min-height: 30px;
  padding: 0 18px;
  background: #0b1220;
  border-top: 1px solid #243244;
  color: #52627a;
  font-size: 11px;
}
.fe-reject { color: #fca5a5; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fe-footer-right { margin-left: auto; }
.fe-loading { flex: 1 1 auto; display: grid; place-items: center; color: #64748b; }
.fe-drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  justify-content: flex-end;
  background: rgba(3, 8, 18, .62);
  backdrop-filter: blur(3px);
}
.fe-drawer {
  width: min(440px, 100%);
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #111827;
  border-left: 1px solid #2c3b52;
  box-shadow: -18px 0 50px rgba(0, 0, 0, .35);
}
.fe-drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 20px 22px 16px;
  border-bottom: 1px solid #243244;
}
.fe-drawer-kicker { color: #60a5fa; font-size: 9.5px; letter-spacing: .14em; }
.fe-drawer-head h2 { margin: 4px 0 0; color: #f1f5f9; font-size: 18px; }
.fe-drawer-close {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #94a3b8;
  font-size: 20px;
  cursor: pointer;
}
.fe-drawer-close:hover { background: #1a2740; color: #fff; }
.fe-drawer-body {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 18px 22px 28px;
}
.fe-drawer-body .field label { color: #94a3b8; }
.fe-drawer-body .field input,
.fe-drawer-body .field textarea,
.fe-drawer-body .field select {
  width: 100%;
  border: 1px solid #2c3b52;
  border-radius: 7px;
  background: #0d1524;
  color: #e2e8f0;
  padding: 9px 11px;
  outline: none;
}
.fe-drawer-body .field input:focus,
.fe-drawer-body .field textarea:focus,
.fe-drawer-body .field select:focus { border-color: #3b82f6; }
.fe-drawer-body .meta { color: #64748b; }
.fe-drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 22px;
  border-top: 1px solid #243244;
}
.fe-drawer-foot .btn { border-radius: 7px; }
.adm-editor-full .adm-modal-wrap { z-index: 100; }
@media (max-width: 860px) {
  .fe-title { font-size: 15px; }
  .fe-topbar { gap: 8px; padding: 0 10px; }
  .fe-top-actions { gap: 4px; }
  .fe-hide-sm { display: none; }
  .fe-body { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) minmax(0, 1fr); }
  .fe-source { border-right: 0; border-bottom: 1px solid #243244; }
  .fe-textarea { padding: 24px 18px 32px; font-size: 13px; }
  .fe-preview-inner { padding: 24px 20px 40px; }
  .fe-footer-right { display: none; }
}
</style>
