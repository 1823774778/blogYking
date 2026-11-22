<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
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
  reviewNote: '',
  slug: '',
  category: '',
  allowComment: true
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
const inlineInput = ref(null)
const headingOpen = ref(false)
const aiMenuOpen = ref(false)
const uploadingInline = ref(false)
const history = ref([])
const historyIndex = ref(-1)
const historyReady = ref(false)
const canUndo = computed(() => historyIndex.value > 0)
const canRedo = computed(() => historyIndex.value >= 0 && historyIndex.value < history.value.length - 1)
let historyTimer = null
let historyApplying = false
const selectionBar = reactive({ show: false, left: 0, top: 0, start: 0, end: 0 })
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
      reviewNote: p.reviewNote || '',
      slug: p.slug || '',
      category: p.category || '',
      allowComment: p.allowComment !== false
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
      top: form.top,
      slug: form.slug.trim(),
      category: form.category.trim(),
      allowComment: form.allowComment
    }
    if (isEdit.value) {
      await http.put(`/admin/posts/${route.params.id}`, payload)
    } else {
      await http.post('/admin/posts', payload)
    }
    settingsOpen.value = false
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

function snapshot() {
  return { title: form.title, content: form.content }
}

function resetHistory() {
  history.value = [snapshot()]
  historyIndex.value = 0
  historyReady.value = true
}

function pushHistory() {
  if (!historyReady.value) return
  const current = history.value[historyIndex.value]
  const next = snapshot()
  if (current && current.title === next.title && current.content === next.content) return
  if (historyIndex.value < history.value.length - 1) {
    history.value = history.value.slice(0, historyIndex.value + 1)
  }
  history.value.push(next)
  if (history.value.length > 80) history.value.shift()
  historyIndex.value = history.value.length - 1
}

function queueHistory() {
  if (!historyReady.value || historyApplying) return
  clearTimeout(historyTimer)
  historyTimer = setTimeout(pushHistory, 450)
}

function applyHistory(index) {
  const state = history.value[index]
  if (!state) return
  historyApplying = true
  form.title = state.title
  form.content = state.content
  nextTick(() => {
    const el = sourceRef.value
    if (el) {
      el.focus()
      const end = form.content.length
      el.setSelectionRange(end, end)
    }
    historyApplying = false
  })
}

function undoEditor() {
  if (!canUndo.value) return
  historyIndex.value -= 1
  applyHistory(historyIndex.value)
}

function redoEditor() {
  if (!canRedo.value) return
  historyIndex.value += 1
  applyHistory(historyIndex.value)
}

function handleEditorKeydown(e) {
  if (!(e.ctrlKey || e.metaKey)) return
  const target = e.target
  if (target !== sourceRef.value) return
  const key = e.key.toLowerCase()
  if (key === 'z' && !e.shiftKey) {
    e.preventDefault()
    undoEditor()
    return
  }
  if ((key === 'z' && e.shiftKey) || key === 'y') {
    e.preventDefault()
    redoEditor()
    return
  }
  if (key === 'b') {
    e.preventDefault()
    insertMarkdown('**', '**', '粗体文字')
  } else if (key === 'i') {
    e.preventDefault()
    insertMarkdown('*', '*', '斜体文字')
  } else if (key === 'k') {
    e.preventDefault()
    insertMarkdown('[', '](https://)', '链接文字')
  } else if (key === 'e') {
    e.preventDefault()
    insertMarkdown('`', '`', '代码')
  }
}

function insertHeading(level) {
  headingOpen.value = false
  insertBlock(`${'#'.repeat(level)} ${level === 1 ? '一级标题' : level === 2 ? '二级标题' : '三级标题'}`)
}

function onSelectionMouseup(e) {
  const el = sourceRef.value
  if (!el || el.selectionStart === el.selectionEnd) {
    selectionBar.show = false
    return
  }
  selectionBar.start = el.selectionStart
  selectionBar.end = el.selectionEnd
  selectionBar.left = Math.max(12, Math.min(window.innerWidth - 240, e.clientX - 110))
  selectionBar.top = Math.max(60, e.clientY - 52)
  selectionBar.show = true
}

function applySelection(kind) {
  const start = selectionBar.start
  const end = selectionBar.end
  const selected = form.content.slice(start, end) || '文字'
  const wrappers = {
    bold: ['**', '**'],
    italic: ['*', '*'],
    h1: ['# ', ''],
    h2: ['## ', ''],
    link: ['[', '](https://)'],
    code: ['`', '`']
  }
  const [before, after] = wrappers[kind] || ['', '']
  form.content = form.content.slice(0, start) + before + selected + after + form.content.slice(end)
  selectionBar.show = false
  nextTick(() => {
    const el = sourceRef.value
    if (!el) return
    el.focus()
    el.setSelectionRange(start + before.length, start + before.length + selected.length)
  })
}

function insertAtCursor(text) {
  const el = sourceRef.value
  const start = el ? el.selectionStart : form.content.length
  const end = el ? el.selectionEnd : form.content.length
  const before = form.content.slice(0, start)
  const lead = before && !before.endsWith('\n') ? '\n\n' : ''
  const block = lead + text + '\n'
  form.content = before + block + form.content.slice(end)
  nextTick(() => {
    if (!el) return
    el.focus()
    const pos = start + block.length
    el.setSelectionRange(pos, pos)
  })
}

function pickInlineFile() {
  inlineInput.value?.click()
}

async function uploadInline(file) {
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  uploadingInline.value = true
  try {
    const res = await http.post('/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    const url = res.data?.url
    if (!url) throw new Error('上传未返回地址')
    const name = (file.name || '附件').replace(/\[|\]/g, '')
    const markdown = file.type.startsWith('image/') ? `![${name}](${url})` : `[${name}](${url})`
    const el = sourceRef.value
    const start = el ? el.selectionStart : form.content.length
    const end = el ? el.selectionEnd : form.content.length
    const before = form.content.slice(0, start)
    const lead = before && !before.endsWith('\n') ? '\n\n' : ''
    form.content = before + lead + markdown + '\n' + form.content.slice(end)
    toast('附件已插入正文')
    nextTick(() => {
      const pos = start + lead.length + markdown.length + 1
      el?.focus()
      el?.setSelectionRange(pos, pos)
    })
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    uploadingInline.value = false
    if (inlineInput.value) inlineInput.value.value = ''
  }
}

function onInlineFile(event) {
  uploadInline(event.target.files?.[0])
}

function onPasteFiles(event) {
  const files = Array.from(event.clipboardData?.files || [])
  if (!files.length) return
  event.preventDefault()
  uploadInline(files[0])
}

function onDropFiles(event) {
  const files = Array.from(event.dataTransfer?.files || [])
  if (!files.length) return
  event.preventDefault()
  uploadInline(files[0])
}

function openArticleInfo() {
  if (!form.title.trim()) {
    toast('请先填写文章标题', 'error')
    return
  }
  if (!form.content.trim()) {
    toast('请先写正文，再保存', 'error')
    return
  }
  if (!form.cover) {
    const match = form.content.match(/!\[([^\]]*)\]\(([^)]+)\)/)
    if (match?.[2] && !match[2].startsWith('data:')) form.cover = match[2]
  }
  settingsOpen.value = true
}

async function saveFromInfo() {
  if (!form.title.trim()) {
    toast('标题不能为空', 'error')
    return
  }
  await save(form.status)
}

watch(() => [form.title, form.content], () => queueHistory())

onBeforeUnmount(() => clearTimeout(historyTimer))

onMounted(async () => {
  await load()
  await loadAiConfig()
  resetHistory()
})
</script>

<template>
  <div
    class="adm-editor-full"
    @keydown="handleEditorKeydown"
    @keydown.ctrl.s.prevent="openArticleInfo"
    @keydown.meta.s.prevent="openArticleInfo"
  >
    <header class="fe-topbar">
      <div class="fe-title-wrap">
        <span class="fe-file-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z" />
            <path d="M14 2v6h6M8 13h8M8 17h5" />
          </svg>
        </span>
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
          {{ syncScroll ? '同步滚动开' : '同步滚动关' }}
        </button>
        <button class="fe-btn fe-btn-ghost" type="button" :disabled="saving" @click="goBack">取消</button>
        <button class="fe-btn fe-btn-primary" type="button" :disabled="saving || loading" @click="openArticleInfo">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
            <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2Z" /><path d="M17 21v-8H7v8M7 3v5h8" />
          </svg>
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </header>

    <div v-if="loading" class="fe-loading">正在加载文章…</div>

    <main v-else class="fe-body">
      <section class="fe-compose">
        <div class="fe-toolbar-wrap">
          <div class="fe-toolbar" aria-label="Markdown 工具栏">
            <button class="fe-tool fe-icon-btn" type="button" title="撤销 (Ctrl+Z)" :disabled="!canUndo" @click="undoEditor">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 14 4 9l5-5" /><path d="M4 9h10a6 6 0 0 1 0 12H8" /></svg>
            </button>
            <button class="fe-tool fe-icon-btn" type="button" title="重做 (Ctrl+Y)" :disabled="!canRedo" @click="redoEditor">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m15 14 5-5-5-5" /><path d="M20 9H10a6 6 0 0 0 0 12h6" /></svg>
            </button>
            <span class="fe-tool-divider"></span>
            <button class="fe-tool fe-tool-strong" type="button" title="粗体 (Ctrl+B)" @click="insertMarkdown('**', '**', '粗体文字')">B</button>
            <button class="fe-tool fe-tool-italic" type="button" title="斜体 (Ctrl+I)" @click="insertMarkdown('*', '*', '斜体文字')">I</button>
            <div class="fe-heading-wrap">
              <button class="fe-tool fe-tool-heading" type="button" title="标题" @click="headingOpen = !headingOpen">H <span>▾</span></button>
              <div v-if="headingOpen" class="fe-heading-menu">
                <button type="button" @click="insertHeading(1)">一级标题 <span>H1</span></button>
                <button type="button" @click="insertHeading(2)">二级标题 <span>H2</span></button>
                <button type="button" @click="insertHeading(3)">三级标题 <span>H3</span></button>
              </div>
            </div>
            <span class="fe-tool-divider"></span>
            <button class="fe-tool fe-icon-btn" type="button" title="插入链接 (Ctrl+K)" @click="insertMarkdown('[', '](https://)', '链接文字')">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" /><path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" /></svg>
            </button>
            <button class="fe-tool fe-icon-btn" type="button" title="插入图片或附件" :disabled="uploadingInline" @click="pickInlineFile">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="16" rx="2" /><circle cx="8.5" cy="9.5" r="1.5" /><path d="m4 17 5-5 4 4 3-3 4 4" /></svg>
            </button>
            <button class="fe-tool" type="button" title="插入表格" @click="insertTable">▦</button>
            <button class="fe-tool" type="button" title="无序列表" @click="insertBlock('- 列表项\n- 列表项')">☰</button>
            <button class="fe-tool" type="button" title="引用" @click="insertBlock('> 引用内容')">❝</button>
            <button class="fe-tool fe-tool-code" type="button" title="代码块 (Ctrl+E)" @click="insertCodeBlock">&lt;/&gt;</button>
            <span class="fe-tool-divider"></span>
            <div class="fe-ai-wrap">
              <button class="fe-tool fe-tool-ai" type="button" title="AI 写作助手" @click="aiMenuOpen = !aiMenuOpen">✦ AI <span>▾</span></button>
              <div v-if="aiMenuOpen" class="fe-ai-menu">
                <button type="button" :disabled="!aiConfig.articleEnabled" @click="aiMenuOpen = false; aiGenOpen = true">
                  <strong>生成文章</strong><small>从主题生成标题、摘要与正文</small>
                </button>
                <button type="button" :disabled="!aiConfig.articleEnabled" @click="aiMenuOpen = false; openReviewPrompt()">
                  <strong>审核文章</strong><small>检查结构、事实与表达</small>
                </button>
                <button type="button" :disabled="!aiConfig.summaryEnabled" @click="aiMenuOpen = false; openSummaryPrompt()">
                  <strong>生成摘要</strong><small>按当前正文压缩摘要</small>
                </button>
              </div>
            </div>
            <span class="fe-tool-spacer"></span>
            <span class="fe-char-count">{{ charCount }} 字</span>
            <button class="fe-tool fe-icon-btn" type="button" title="下载 Markdown" @click="downloadMarkdown">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3v12m0 0 5-5m-5 5-5-5" /><path d="M4 19h16" /></svg>
            </button>
            <button class="fe-tool fe-icon-btn" type="button" title="文章设置" @click="openArticleInfo">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3h.1a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9v.1a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z" /></svg>
            </button>
          </div>
        </div>

        <div class="fe-write-area">
          <textarea
            ref="sourceRef"
            v-model="form.content"
            class="fe-textarea"
            placeholder="在此输入 Markdown 内容……"
            spellcheck="false"
            @scroll.passive="syncFromSource(); selectionBar.show = false"
            @mouseup="onSelectionMouseup"
            @paste="onPasteFiles"
            @drop="onDropFiles"
            @dragover.prevent
          ></textarea>
          <div class="fe-write-status">
            <span>本地历史已开启</span>
            <span v-if="uploadingInline" class="fe-uploading">附件上传中…</span>
            <span v-else>支持粘贴或拖拽图片</span>
          </div>
        </div>
      </section>

      <section class="fe-preview" @scroll.passive="syncFromPreview">
        <div class="fe-preview-head">
          <span>预览</span>
          <span class="fe-preview-live">LIVE</span>
        </div>
        <div ref="previewRef" class="fe-preview-inner">
          <MarkdownBody v-if="form.content.trim()" :content="form.content" />
          <div v-else class="fe-empty">
            <span aria-hidden="true">✦</span>
            <p>左侧开始书写，这里会实时预览排版</p>
          </div>
        </div>
      </section>
    </main>

    <footer class="fe-footer">
      <span>Markdown · GFM</span>
      <span v-if="form.reviewNote && form.status === 'REJECTED'" class="fe-reject">退回意见：{{ form.reviewNote }}</span>
      <span class="fe-footer-right">Ctrl / ⌘ + S 保存 · Ctrl + Z 撤销</span>
    </footer>

    <div v-if="selectionBar.show" class="fe-selection-bar" :style="{ left: selectionBar.left + 'px', top: selectionBar.top + 'px' }">
      <button type="button" title="加粗" @click="applySelection('bold')">B</button>
      <button type="button" title="斜体" @click="applySelection('italic')">I</button>
      <button type="button" title="一级标题" @click="applySelection('h1')">H1</button>
      <button type="button" title="二级标题" @click="applySelection('h2')">H2</button>
      <button type="button" title="链接" @click="applySelection('link')">↗</button>
      <button type="button" title="代码" @click="applySelection('code')">&lt;/&gt;</button>
    </div>

    <input ref="inlineInput" type="file" accept="image/*,.pdf,.doc,.docx,.zip,.mp3,.mp4" hidden @change="onInlineFile" />

    <div v-if="settingsOpen" class="fe-info-mask" @click.self="settingsOpen = false">
      <div class="fe-info" role="dialog" aria-label="文章设置">
        <header class="fe-info-head">
          <div>
            <span class="fe-info-kicker">ARTICLE INFO</span>
            <h2>{{ isEdit ? '文章设置' : '准备发布' }}</h2>
            <p>确认标题、封面与分类后保存。正文与标题已同步。</p>
          </div>
          <button class="fe-info-close" type="button" aria-label="关闭" @click="settingsOpen = false">×</button>
        </header>
        <div class="fe-info-body">
          <div class="fe-info-grid">
            <div class="field span-2">
              <label for="infoTitle">文章标题</label>
              <input id="infoTitle" v-model="form.title" maxlength="160" />
            </div>
            <div class="field span-2">
              <label for="summary">摘要</label>
              <textarea id="summary" v-model="form.summary" maxlength="200" placeholder="留空则自动截取正文开头"></textarea>
              <div class="fe-summary-row">
                <button class="btn btn-ghost btn-sm" type="button" :disabled="generating || !aiConfig.summaryEnabled" @click="openSummaryPrompt">{{ generating ? '生成中…' : '✦ AI 生成摘要' }}</button>
                <span>{{ aiConfig.summaryEnabled ? '可编辑本次提示词' : 'AI 摘要已在站点设置中关闭' }}</span>
              </div>
            </div>
            <div class="field">
              <label for="tags">标签</label>
              <input id="tags" v-model="form.tags" placeholder="用逗号分隔，如 Java,随笔" />
            </div>
            <div class="field">
              <label for="category">文章分类</label>
              <input id="category" v-model="form.category" list="category-options" maxlength="32" placeholder="例如 技术、生活、随笔" />
              <datalist id="category-options">
                <option value="技术" /><option value="生活" /><option value="随笔" /><option value="思考" />
              </datalist>
            </div>
            <div class="field">
              <label for="slug">文章别名</label>
              <input id="slug" v-model="form.slug" maxlength="128" placeholder="hello-world" />
              <span class="fe-field-note">小写字母、数字、连字符和下划线</span>
            </div>
            <div class="field">
              <label for="statusSelect">文章状态</label>
              <select id="statusSelect" v-model="form.status">
                <option value="DRAFT">草稿</option>
                <option value="PENDING">待审核</option>
                <option value="PUBLISHED">已发布</option>
              </select>
            </div>
            <div class="field span-2">
              <label>封面</label>
              <div class="fe-cover-row">
                <div class="fe-cover-preview" :class="{ empty: !form.cover }">
                  <img v-if="form.cover" :src="form.cover" alt="封面预览" />
                  <span v-else>未设置封面</span>
                </div>
                <div class="fe-cover-actions">
                  <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickCover">{{ uploading ? '上传中…' : form.cover ? '更换封面' : '上传封面' }}</button>
                  <button v-if="form.cover" class="btn-text" type="button" @click="form.cover = ''">移除</button>
                  <span>建议 16:9，首张正文图片会自动作为封面候选</span>
                </div>
              </div>
              <input ref="coverInput" type="file" accept="image/*" hidden @change="onCover" />
            </div>
            <div class="field span-2">
              <div class="fe-check-row">
                <label class="fe-check"><input v-model="form.top" type="checkbox" /> 置顶这篇文章</label>
                <label class="fe-check"><input v-model="form.allowComment" type="checkbox" /> 开启本文章评论区</label>
              </div>
            </div>
          </div>
          <p class="fe-info-note">正文支持标题、列表、代码块、引用、表格与图片。保存前可在右侧预览检查排版。</p>
        </div>
        <footer class="fe-info-foot">
          <span>{{ statusText }}</span>
          <div>
            <button class="btn btn-ghost" type="button" @click="settingsOpen = false">继续编辑</button>
            <button class="btn" type="button" :disabled="saving" @click="saveFromInfo">{{ saving ? '保存中…' : form.status === 'DRAFT' ? '保存草稿' : form.status === 'PENDING' ? '提交审核' : '保存并发布' }}</button>
          </div>
        </footer>
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
  background: #fff;
  color: #1f2937;
  font-family: var(--sans, "Inter", "PingFang SC", "Microsoft YaHei", sans-serif);
}

.fe-topbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 64px;
  padding: 0 22px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}
.fe-title-wrap { display: flex; align-items: center; gap: 11px; flex: 1 1 auto; min-width: 0; }
.fe-file-icon { display: grid; place-items: center; width: 30px; height: 30px; flex: 0 0 30px; color: #2563eb; }
.fe-file-icon svg { width: 23px; height: 23px; }
.fe-title {
  flex: 1 1 auto;
  min-width: 0;
  height: 42px;
  padding: 0 4px;
  border: 0;
  background: transparent;
  color: #111827;
  font-size: 20px;
  font-weight: 650;
  outline: none;
}
.fe-title::placeholder { color: #9ca3af; font-weight: 500; }
.fe-top-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.fe-sync {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 11px;
  border: 0;
  border-radius: 7px;
  background: #f3f4f6;
  color: #4b5563;
  font-size: 12px;
  cursor: pointer;
  transition: .16s ease;
}
.fe-sync svg { width: 14px; height: 14px; }
.fe-sync:hover { background: #e5e7eb; }
.fe-sync.on { background: #dbeafe; color: #1d4ed8; }
.fe-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: 7px;
  font-size: 13px;
  cursor: pointer;
  transition: .16s ease;
}
.fe-btn svg { width: 15px; height: 15px; }
.fe-btn:disabled { opacity: .55; cursor: default; }
.fe-btn-ghost { border-color: #e5e7eb; background: #fff; color: #4b5563; }
.fe-btn-ghost:hover:not(:disabled) { background: #f9fafb; border-color: #d1d5db; }
.fe-btn-primary { background: #2563eb; color: #fff; font-weight: 600; }
.fe-btn-primary:hover:not(:disabled) { background: #1d4ed8; }

.fe-body {
  flex: 1 1 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-height: 0;
  background: #fff;
}
.fe-compose {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border-right: 1px solid #e5e7eb;
  background: #fff;
}
.fe-toolbar-wrap { position: relative; flex: 0 0 auto; border-bottom: 1px solid #e5e7eb; background: #fafafa; }
.fe-toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  min-height: 48px;
  padding: 0 10px;
  overflow-x: auto;
  scrollbar-width: none;
}
.fe-toolbar::-webkit-scrollbar { display: none; }
.fe-tool {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  height: 32px;
  padding: 0 8px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #4b5563;
  font-size: 13px;
  cursor: pointer;
  transition: .16s ease;
}
.fe-tool:hover:not(:disabled) { background: #eceff3; color: #111827; }
.fe-tool:disabled { opacity: .35; cursor: default; }
.fe-icon-btn { min-width: 32px; padding: 0; }
.fe-icon-btn svg { width: 17px; height: 17px; }
.fe-tool-strong { font-weight: 800; color: #111827; }
.fe-tool-italic { font-family: Georgia, serif; font-style: italic; color: #111827; }
.fe-tool-heading { gap: 4px; font-weight: 700; }
.fe-tool-heading > span { color: #9ca3af; font-size: 9px; font-weight: 400; }
.fe-tool-code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.fe-tool-ai { gap: 4px; color: #1d4ed8; background: #eff6ff; }
.fe-tool-ai > span { color: #93b4f5; font-size: 9px; }
.fe-ai-wrap { position: relative; flex: 0 0 auto; }
.fe-ai-menu {
  position: absolute;
  top: 38px;
  left: 0;
  z-index: 9;
  width: 224px;
  padding: 6px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 14px 36px rgba(15, 23, 42, .14);
}
.fe-ai-menu button {
  display: grid;
  gap: 3px;
  width: 100%;
  padding: 9px 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.fe-ai-menu button:hover:not(:disabled) { background: #f5f7fb; }
.fe-ai-menu button:disabled { opacity: .45; cursor: default; }
.fe-ai-menu strong { color: #1f2937; font-size: 12px; }
.fe-ai-menu small { color: #9ca3af; font-size: 9.5px; }
.fe-tool-divider { flex: 0 0 auto; width: 1px; height: 20px; margin: 0 5px; background: #e5e7eb; }
.fe-tool-spacer { flex: 1 1 auto; min-width: 6px; }
.fe-char-count { flex: 0 0 auto; padding: 0 8px; color: #9ca3af; font-size: 11px; font-variant-numeric: tabular-nums; }

.fe-heading-wrap { position: relative; flex: 0 0 auto; }
.fe-heading-menu {
  position: absolute;
  top: 38px;
  left: 0;
  z-index: 8;
  width: 148px;
  padding: 6px;
  border: 1px solid #e5e7eb;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, .12);
}
.fe-heading-menu button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 8px 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #374151;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.fe-heading-menu button:hover { background: #f3f4f6; }
.fe-heading-menu button span { color: #9ca3af; font-size: 10px; }

.fe-write-area { position: relative; display: flex; flex: 1 1 auto; flex-direction: column; min-height: 0; }
.fe-textarea {
  display: block;
  flex: 1 1 auto;
  width: 100%;
  min-height: 0;
  padding: 20px 30px 36vh;
  border: 0;
  outline: none;
  resize: none;
  background: #fff;
  color: #1f2937;
  font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
  font-size: 14px;
  line-height: 1.85;
  tab-size: 2;
}
.fe-textarea::placeholder { color: #aeb6c2; }
.fe-write-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: 0 0 auto;
  min-height: 32px;
  padding: 0 16px;
  border-top: 1px solid #f0f1f3;
  background: #fafafa;
  color: #9ca3af;
  font-size: 10px;
}
.fe-uploading { color: #2563eb; }

.fe-preview { display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: hidden; background: #fff; }
.fe-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 auto;
  min-height: 48px;
  padding: 0 26px;
  border-bottom: 1px solid #f0f1f3;
  color: #374151;
  font-size: 12px;
  font-weight: 600;
}
.fe-preview-live { color: #059669; font-size: 9px; font-weight: 700; letter-spacing: .14em; }
.fe-preview-live::before { content: ""; display: inline-block; width: 5px; height: 5px; margin-right: 5px; border-radius: 50%; background: #10b981; vertical-align: 1px; }
.fe-preview-inner { flex: 1 1 auto; overflow-y: auto; padding: 24px 32px 80px; }
.fe-empty { display: grid; place-content: center; justify-items: center; gap: 10px; min-height: 60vh; color: #9ca3af; text-align: center; }
.fe-empty > span { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 12px; background: #f3f4f6; color: #6b7280; font-size: 18px; }
.fe-empty p { margin: 0; font-size: 12px; }
.fe-preview :deep(.markdown) { color: #374151; font-size: 15px; line-height: 1.9; }
.fe-preview :deep(.markdown h1),
.fe-preview :deep(.markdown h2),
.fe-preview :deep(.markdown h3),
.fe-preview :deep(.markdown h4) { color: #111827; }
.fe-preview :deep(.markdown a) { color: #2563eb; }
.fe-preview :deep(.markdown code) { background: #f3f4f6; color: #b91c1c; border-radius: 4px; }
.fe-preview :deep(.markdown pre) { background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 10px; }
.fe-preview :deep(.markdown pre code) { color: #334155; }
.fe-preview :deep(.markdown blockquote) { border-left: 3px solid #cbd5e1; background: #f8fafc; color: #64748b; border-radius: 0 8px 8px 0; }
.fe-preview :deep(.markdown table) { border-color: #e5e7eb; }
.fe-preview :deep(.markdown table th) { background: #f9fafb; color: #111827; border-color: #e5e7eb; }
.fe-preview :deep(.markdown table td) { border-color: #e5e7eb; }
.fe-preview :deep(.markdown img) { max-width: 100%; border-radius: 10px; }

.fe-footer {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 18px;
  min-height: 30px;
  padding: 0 20px;
  border-top: 1px solid #e5e7eb;
  background: #fafafa;
  color: #9ca3af;
  font-size: 10.5px;
}
.fe-reject { overflow: hidden; color: #b91c1c; text-overflow: ellipsis; white-space: nowrap; }
.fe-footer-right { margin-left: auto; }
.fe-loading { flex: 1 1 auto; display: grid; place-items: center; background: #fff; color: #9ca3af; }

.fe-selection-bar {
  position: fixed;
  z-index: 90;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px;
  border-radius: 9px;
  background: #111827;
  box-shadow: 0 10px 28px rgba(0, 0, 0, .24);
}
.fe-selection-bar button {
  min-width: 30px;
  height: 28px;
  padding: 0 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #e5e7eb;
  font-size: 11px;
  cursor: pointer;
}
.fe-selection-bar button:hover { background: #374151; color: #fff; }

.fe-info-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, .38);
  backdrop-filter: blur(4px);
}
.fe-info {
  display: flex;
  flex-direction: column;
  width: min(780px, 100%);
  max-height: min(88vh, 780px);
  overflow: hidden;
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 28px 80px rgba(2, 6, 23, .28);
}
.fe-info-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 21px 24px 17px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}
.fe-info-kicker { color: #2563eb; font-size: 9.5px; font-weight: 600; letter-spacing: .15em; }
.fe-info-head h2 { margin: 5px 0 0; color: #111827; font-size: 19px; }
.fe-info-head p { margin: 5px 0 0; color: #9ca3af; font-size: 10.5px; }
.fe-info-close {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #9ca3af;
  font-size: 20px;
  cursor: pointer;
}
.fe-info-close:hover { background: #f3f4f6; color: #111827; }
.fe-info-body { flex: 1 1 auto; overflow-y: auto; padding: 21px 24px 18px; }
.fe-info-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 16px; }
.span-2 { grid-column: span 2; }
.fe-info .field { display: grid; gap: 6px; min-width: 0; }
.fe-info .field label { color: #4b5563; font-size: 11.5px; font-weight: 600; }
.fe-info .field input,
.fe-info .field textarea,
.fe-info .field select {
  width: 100%;
  min-height: 38px;
  padding: 8px 11px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #1f2937;
  font-size: 12.5px;
  outline: none;
  transition: .16s ease;
}
.fe-info .field input:focus,
.fe-info .field textarea:focus,
.fe-info .field select:focus { border-color: #93c5fd; box-shadow: 0 0 0 3px rgba(37, 99, 235, .08); }
.fe-info .field textarea { min-height: 78px; resize: vertical; }
.fe-field-note { color: #9ca3af; font-size: 9.5px; }
.fe-summary-row { display: flex; align-items: center; gap: 10px; }
.fe-summary-row span { color: #9ca3af; font-size: 9.5px; }
.fe-cover-row { display: flex; align-items: center; gap: 14px; }
.fe-cover-preview {
  display: grid;
  place-items: center;
  width: 168px;
  aspect-ratio: 16/9;
  flex: 0 0 168px;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 9px;
  background: #f9fafb;
}
.fe-cover-preview img { width: 100%; height: 100%; object-fit: cover; }
.fe-cover-preview.empty { color: #9ca3af; font-size: 10.5px; border-style: dashed; }
.fe-cover-actions { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
.fe-cover-actions > span { flex: 1 0 100%; color: #9ca3af; font-size: 9.5px; line-height: 1.5; }
.fe-check-row { display: flex; align-items: center; gap: 24px; padding: 2px 0; }
.fe-info .field .fe-check { display: inline-flex; width: auto; flex-direction: row; align-items: center; gap: 7px; color: #4b5563; font-size: 12px; cursor: pointer; }
.fe-info .field .fe-check input { width: 16px; height: 16px; min-height: 0; flex: 0 0 16px; padding: 0; accent-color: #2563eb; }
.fe-info-note { margin: 15px 0 0; padding: 9px 11px; border-radius: 8px; background: #f9fafb; color: #9ca3af; font-size: 10px; line-height: 1.65; }
.fe-info-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 24px;
  border-top: 1px solid #e5e7eb;
  background: #fff;
}
.fe-info-foot > span { color: #9ca3af; font-size: 10.5px; }
.fe-info-foot > div { display: flex; gap: 8px; }
.fe-info-foot .btn { min-height: 34px; padding: 0 15px; border-radius: 7px; font-size: 12px; }

.adm-editor-full .adm-modal-wrap { z-index: 120; }
.adm-editor-full .adm-modal { background: #fff; color: #1f2937; }

@media (max-width: 900px) {
  .fe-topbar { min-height: 56px; padding: 0 12px; gap: 8px; }
  .fe-title { font-size: 15px; height: 38px; }
  .fe-file-icon { width: 24px; flex-basis: 24px; }
  .fe-file-icon svg { width: 19px; height: 19px; }
  .fe-top-actions { gap: 4px; }
  .fe-sync { display: none; }
  .fe-btn { height: 33px; padding: 0 11px; font-size: 12px; }
  .fe-body { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) minmax(0, 1fr); }
  .fe-compose { border-right: 0; border-bottom: 1px solid #e5e7eb; }
  .fe-textarea { padding: 16px 18px 30vh; font-size: 13px; }
  .fe-preview-head { min-height: 38px; padding: 0 16px; }
  .fe-preview-inner { padding: 18px 18px 40px; }
  .fe-footer-right { display: none; }
  .fe-info-mask { padding: 10px; }
  .fe-info-grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: span 1; }
  .fe-cover-preview { width: 120px; flex-basis: 120px; }
  .fe-check-row { align-items: flex-start; flex-direction: column; gap: 9px; }
}
</style>
