<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'
import OtaPanel from '../../components/admin/OtaPanel.vue'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'smtp'))
const config = ref({})
const loading = ref(false)
const savingSmtp = ref(false)
const savingAi = ref(false)
const savingRegistration = ref(false)
const savingMaintenance = ref(false)
const overview = ref(null)
const exporting = ref(false)
const clearingCache = ref(false)
const testingMail = ref(false)
const testingAi = ref(false)
const mailTo = ref('')
const smtpDraft = ref({})
const smtpDraftReady = ref(false)
const mailTemplateDefaults = ref({})
const templateKind = ref('registration')
const htmlEditor = ref(null)
const aiTestResult = ref('')
const smtpOpen = ref(false)
const aiOpen = ref(false)
const loadingModels = ref(false)
const aiModels = ref([])
const modelError = ref('')
const version = ref(null)
const ota = ref(null)
const health = ref(null)
const restarting = ref(false)
const restartMessage = ref('')
const promptOpen = ref(false)
const promptFeature = ref('article')
const promptSaving = ref(false)
const featureSaving = ref('')
const defaultPrompts = ref({})
const promptDraft = reactive({
  aiArticleGeneratePrompt: '',
  aiArticleReviewPrompt: '',
  aiCommentReviewPrompt: '',
  aiSummaryPrompt: ''
})

const mailTemplateKinds = [
  {
    key: 'registration',
    label: '注册验证码',
    subjectKey: 'mailTemplateRegistrationSubject',
    htmlKey: 'mailTemplateRegistrationHtml'
  },
  {
    key: 'reset',
    label: '找回密码',
    subjectKey: 'mailTemplateResetSubject',
    htmlKey: 'mailTemplateResetHtml'
  },
  {
    key: 'test',
    label: '测试邮件',
    subjectKey: 'mailTemplateTestSubject',
    htmlKey: 'mailTemplateTestHtml'
  }
]

const smtpKeys = [
  'smtpEnabled', 'smtpHost', 'smtpPort', 'smtpUsername', 'smtpPassword',
  'smtpSenderName', 'smtpSsl', 'smtpStarttls',
  'mailTemplateRegistrationSubject', 'mailTemplateRegistrationHtml',
  'mailTemplateResetSubject', 'mailTemplateResetHtml',
  'mailTemplateTestSubject', 'mailTemplateTestHtml'
]

const currentTemplate = computed(() =>
  mailTemplateKinds.find((item) => item.key === templateKind.value) || mailTemplateKinds[0]
)

const previewBaseUrl = computed(() => {
  const raw = String(config.value.siteUrl || '').trim().replace(/\/+$/, '')
  if (!raw) return 'https://your-blog.example.com'
  return /^https?:\/\//i.test(raw) ? raw : `https://${raw}`
})

const previewVars = computed(() => ({
  siteName: config.value.siteName || '墨记',
  siteUrl: previewBaseUrl.value,
  year: String(new Date().getFullYear()),
  code: '836214',
  expireMinutes: '10',
  link: `${previewBaseUrl.value}/reset-password?token=demo-token`,
  sentAt: '2026-11-20 18:30',
  email: 'reader@example.com'
}))

const previewSubject = computed(() =>
  replaceTokens(smtpDraft.value[currentTemplate.value.subjectKey] || '', previewVars.value)
)

const previewHtml = computed(() =>
  replaceTokens(smtpDraft.value[currentTemplate.value.htmlKey] || '', previewVars.value)
)

const templatePlaceholders = computed(() => {
  const common = ['siteName', 'siteUrl', 'year']
  if (templateKind.value === 'registration') return [...common, 'code', 'expireMinutes', 'email']
  if (templateKind.value === 'reset') return [...common, 'link', 'expireMinutes']
  return [...common, 'sentAt']
})

const aiFeatures = [
  {
    key: 'article',
    enabledKey: 'aiArticleEnabled',
    title: 'AI 文章',
    desc: '生成草稿、审核文章'
  },
  {
    key: 'comment',
    enabledKey: 'aiCommentEnabled',
    title: 'AI 评论',
    desc: '审核评论内容'
  },
  {
    key: 'summary',
    enabledKey: 'aiSummaryEnabled',
    title: 'AI 摘要',
    desc: '编辑器内生成文章摘要'
  },
  {
    key: 'announcement',
    enabledKey: 'aiAnnouncementEnabled',
    title: 'AI 公告',
    desc: '在公告管理中按要点起草公告'
  }
]

const promptFields = computed(() => ({
  article: [
    { key: 'aiArticleGeneratePrompt', label: 'AI 生成文章提示词' },
    { key: 'aiArticleReviewPrompt', label: 'AI 审核文章提示词' }
  ],
  comment: [
    { key: 'aiCommentReviewPrompt', label: 'AI 审核评论提示词' }
  ],
  summary: [
    { key: 'aiSummaryPrompt', label: 'AI 摘要提示词' }
  ],
  announcement: [
    { key: 'aiAnnouncementPrompt', label: 'AI 公告提示词' }
  ]
}[promptFeature.value] || []))

const promptFeatureTitle = computed(() => ({
  article: 'AI 文章提示词',
  comment: 'AI 评论提示词',
  summary: 'AI 摘要提示词',
  announcement: 'AI 公告提示词'
}[promptFeature.value] || 'AI 提示词'))

function boolValue(value) {
  return String(value).toLowerCase() === 'true'
}

function setBool(key, event) {
  config.value[key] = event.target.checked ? 'true' : 'false'
}

async function loadConfig() {
  loading.value = true
  try {
    config.value = (await http.get('/admin/config')).data
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function persistConfig() {
  const res = await http.put('/admin/config', config.value)
  config.value = res.data
  return res.data
}

async function loadMailTemplateDefaults() {
  if (Object.keys(mailTemplateDefaults.value).length) return
  try {
    const res = await http.get('/admin/mail/templates')
    mailTemplateDefaults.value = res.data?.defaults || {}
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function buildSmtpDraft() {
  await loadMailTemplateDefaults()
  const draft = {}
  for (const key of smtpKeys) {
    draft[key] = config.value[key] || ''
  }
  for (const item of mailTemplateKinds) {
    if (!draft[item.subjectKey]) draft[item.subjectKey] = mailTemplateDefaults.value[item.subjectKey] || ''
    if (!draft[item.htmlKey]) draft[item.htmlKey] = mailTemplateDefaults.value[item.htmlKey] || ''
  }
  smtpDraft.value = draft
  smtpDraftReady.value = true
}

async function ensureSmtpDraft() {
  if (!smtpDraftReady.value) await buildSmtpDraft()
  return smtpDraft.value
}

async function openSmtp() {
  await ensureSmtpDraft()
  templateKind.value = 'registration'
  mailTo.value = ''
  smtpOpen.value = true
}

function setDraftBool(key, event) {
  smtpDraft.value[key] = event.target.checked ? 'true' : 'false'
}

function replaceTokens(raw, vars) {
  return String(raw || '').replace(/\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g, (_, key) => vars[key] ?? '')
}

function insertPlaceholder(name) {
  const token = `{{${name}}}`
  const editor = htmlEditor.value
  if (!editor) {
    smtpDraft.value[currentTemplate.value.htmlKey] = (smtpDraft.value[currentTemplate.value.htmlKey] || '') + token
    return
  }
  const start = editor.selectionStart ?? editor.value.length
  const end = editor.selectionEnd ?? start
  const current = editor.value || ''
  smtpDraft.value[currentTemplate.value.htmlKey] = current.slice(0, start) + token + current.slice(end)
  requestAnimationFrame(() => {
    editor.focus()
    editor.selectionStart = editor.selectionEnd = start + token.length
  })
}

function resetCurrentTemplate() {
  const item = currentTemplate.value
  smtpDraft.value[item.subjectKey] = mailTemplateDefaults.value[item.subjectKey] || ''
  smtpDraft.value[item.htmlKey] = mailTemplateDefaults.value[item.htmlKey] || ''
  toast('已恢复默认模板，保存后生效')
}

function smtpPayload() {
  const payload = {}
  for (const key of smtpKeys) {
    payload[key] = smtpDraft.value[key] || ''
  }
  return payload
}

async function persistSmtpDraft() {
  const res = await http.put('/admin/config', smtpPayload())
  config.value = { ...config.value, ...res.data }
  return res.data
}

async function saveSmtpTemplates() {
  savingSmtp.value = true
  try {
    await ensureSmtpDraft()
    await persistSmtpDraft()
    toast('邮件模板已保存并生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingSmtp.value = false
  }
}

async function saveSmtp() {
  savingSmtp.value = true
  try {
    await persistSmtpDraft()
    smtpOpen.value = false
    toast('邮件设置已保存并生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingSmtp.value = false
  }
}

async function testMail() {
  if (!mailTo.value.trim()) {
    toast('请输入测试收件邮箱', 'error')
    return
  }
  testingMail.value = true
  try {
    await persistSmtpDraft()
    await http.post('/admin/mail/test', { to: mailTo.value.trim() })
    toast('设置已保存，测试邮件已发送')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    testingMail.value = false
  }
}

async function saveAi() {
  savingAi.value = true
  try {
    await persistConfig()
    aiOpen.value = false
    toast('AI 设置已保存')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingAi.value = false
  }
}

async function testAi() {
  testingAi.value = true
  aiTestResult.value = ''
  try {
    const res = await http.post('/admin/ai/test')
    aiTestResult.value = res.data.message
    toast(res.data.message)
  } catch (e) {
    aiTestResult.value = e.message
    toast(e.message, 'error')
  } finally {
    testingAi.value = false
  }
}

async function fetchModels() {
  loadingModels.value = true
  modelError.value = ''
  aiModels.value = []
  try {
    // 先保存当前 Base URL/Key，否则接口会读取旧配置。
    await persistConfig()
    const res = await http.get('/admin/ai/models', { timeout: 40000 })
    aiModels.value = res.data.models || []
    if (!aiModels.value.length) modelError.value = '接口返回了空模型列表'
    else toast(`已获取 ${aiModels.value.length} 个可用模型`)
  } catch (e) {
    modelError.value = e.message
    toast(e.message, 'error')
  } finally {
    loadingModels.value = false
  }
}

function applyModel(id) {
  config.value.aiModel = id
  toast(`已选择模型 ${id}`)
}

async function saveRegistration() {
  savingRegistration.value = true
  try {
    if (boolValue(config.value.registrationInviteEnabled) && !String(config.value.registrationInviteCode || '').trim()) {
      toast('已开启邀请码，请先填写邀请码', 'error')
      return
    }
    const minLength = Number(config.value.registrationMinPasswordLength || 6)
    if (!Number.isInteger(minLength) || minLength < 6 || minLength > 64) {
      toast('密码最小长度需为 6–64 位', 'error')
      return
    }
    const maxUsers = Number(config.value.registrationMaxUsers || 0)
    if (!Number.isInteger(maxUsers) || maxUsers < 0) {
      toast('用户上限需为 0 或正整数', 'error')
      return
    }
    config.value.registrationMinPasswordLength = String(minLength)
    config.value.registrationMaxUsers = String(maxUsers)
    await persistConfig()
    toast('注册设置已保存')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingRegistration.value = false
  }
}

async function saveMaintenance() {
  savingMaintenance.value = true
  try {
    await persistConfig()
    await loadMaintenanceOverview()
    toast('站点模式已保存')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingMaintenance.value = false
  }
}

async function loadMaintenanceOverview() {
  try {
    overview.value = (await http.get('/admin/maintenance/overview')).data
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function exportData() {
  if (exporting.value) return
  exporting.value = true
  try {
    const blob = await http.get('/admin/maintenance/export', { responseType: 'blob', timeout: 60000 })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `blog-backup-${new Date().toISOString().slice(0, 19).replace(/\D/g, '')}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    toast('数据快照已导出')
  } catch (e) {
    toast(e.message || '导出失败', 'error')
  } finally {
    exporting.value = false
  }
}

async function clearOtaCache() {
  if (clearingCache.value) return
  if (!window.confirm('确定清理更新缓存与下载产物吗？这不会影响当前运行版本。')) return
  clearingCache.value = true
  try {
    await http.post('/admin/ota/cache/clear')
    toast('更新缓存已清理')
    loadRuntime()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    clearingCache.value = false
  }
}

function durationText(seconds) {
  const value = Number(seconds)
  if (!Number.isFinite(value) || value <= 0) return '—'
  const days = Math.floor(value / 86400)
  const hours = Math.floor((value % 86400) / 3600)
  const minutes = Math.floor((value % 3600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分钟`
  return `${minutes} 分钟`
}

async function toggleAiFeature(feature, event) {
  const key = feature.enabledKey
  const previous = config.value[key]
  const next = event.target.checked ? 'true' : 'false'
  config.value[key] = next
  featureSaving.value = key
  try {
    await persistConfig()
    toast(`${feature.title}已${next === 'true' ? '开启' : '关闭'}`)
  } catch (e) {
    config.value[key] = previous
    toast(e.message, 'error')
  } finally {
    featureSaving.value = ''
  }
}

async function loadDefaultPrompts() {
  if (Object.keys(defaultPrompts.value).length) return
  try {
    defaultPrompts.value = (await http.get('/admin/ai/default-prompts')).data || {}
  } catch (e) {
    toast(e.message, 'error')
  }
}

function openPrompt(feature) {
  promptFeature.value = feature
  for (const field of promptFields.value) {
    promptDraft[field.key] = config.value[field.key] || ''
  }
  promptOpen.value = true
  loadDefaultPrompts()
}

function resetPrompt() {
  for (const field of promptFields.value) {
    promptDraft[field.key] = defaultPrompts.value[field.key] || ''
  }
  toast('已恢复默认提示词，点击保存后生效')
}

async function savePrompt() {
  for (const field of promptFields.value) {
    if (!String(promptDraft[field.key] || '').trim()) {
      toast(`请填写${field.label}`, 'error')
      return
    }
  }
  promptSaving.value = true
  try {
    for (const field of promptFields.value) {
      config.value[field.key] = String(promptDraft[field.key]).trim()
    }
    await persistConfig()
    promptOpen.value = false
    toast('提示词已保存，后续 AI 操作立即生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    promptSaving.value = false
  }
}

async function loadRuntime() {
  try {
    const [v, o, h] = await Promise.all([
      http.get('/system/version'),
      http.get('/admin/ota/status'),
      http.get('/system/health')
    ])
    version.value = v.data
    ota.value = o.data
    health.value = h.data
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function restartSite() {
  if (restarting.value) return
  if (!ota.value?.canAutoUpdate) {
    toast(ota.value?.modeNote || '当前运行模式不支持自动重启', 'error')
    return
  }
  if (!window.confirm('确定重启站点吗？重启期间站点会短暂不可用，通常需要 10–40 秒恢复。')) return
  const baseline = version.value?.instanceId
  restarting.value = true
  restartMessage.value = '正在提交重启请求…'
  try {
    await http.post('/admin/ota/restart', null, { timeout: 15000 })
    restartMessage.value = '正在等待新进程启动…'
  } catch (e) {
    if (e.status) {
      restarting.value = false
      toast(e.message, 'error')
      return
    }
    restartMessage.value = '服务正在重启，等待重新上线…'
  }
  const startedAt = Date.now()
  const tick = async () => {
    try {
      const res = await http.get('/system/version', { timeout: 5000 })
      if (res.data.instanceId && res.data.instanceId !== baseline) {
        version.value = res.data
        restarting.value = false
        restartMessage.value = '站点已恢复'
        toast(`站点已重启，运行版本 ${res.data.version}`)
        loadRuntime()
        return
      }
    } catch (e) {
      // 重启过程中短暂不可用
    }
    if (Date.now() - startedAt > 120000) {
      restarting.value = false
      restartMessage.value = ''
      toast('重启仍在进行或服务未恢复，请稍后刷新检查', 'error')
      return
    }
    setTimeout(tick, 2000)
  }
  setTimeout(tick, 2500)
}

async function copyVersion() {
  try {
    await navigator.clipboard.writeText(version.value?.version || '')
    toast('版本号已复制')
  } catch (e) {
    toast('复制失败，请手动选择', 'error')
  }
}

function otaModeLabel(mode) {
  return ({ standalone: '独立运行', systemd: 'systemd', docker: 'Docker', dev: '开发模式', disabled: '已关闭' })[mode] || mode || '—'
}

function shortSha(sha) {
  return sha ? `${sha.slice(0, 12)}…` : '—'
}

function formatBytes(bytes) {
  if (!bytes) return '—'
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

onMounted(async () => {
  await Promise.all([loadConfig(), loadRuntime(), loadMailTemplateDefaults(), loadMaintenanceOverview()])
  await ensureSmtpDraft()
})
</script>

<template>
  <div class="adm-page">
    <div v-if="loading" class="adm-loading">正在读取设置…</div>

    <template v-else>
      <div v-if="activeTab === 'smtp'" class="smtp-stage">
        <header class="smtp-page-head">
          <div class="smtp-page-title">
            <span class="adm-card-kicker">MAIL STUDIO</span>
            <h1>邮件</h1>
            <p>编辑验证码与找回密码邮件，右侧同步预览最终收件效果。发信连接通过右上角单独配置。</p>
          </div>
          <div class="smtp-page-actions">
            <span class="adm-status" :class="boolValue(config.smtpEnabled) ? 'is-published' : 'is-draft'">
              {{ boolValue(config.smtpEnabled) ? 'SMTP 已启用' : 'SMTP 未启用' }}
            </span>
            <button class="btn" type="button" @click="openSmtp">配置邮箱</button>
          </div>
        </header>

        <div class="mail-studio smtp-page-studio">
          <section class="mail-edit-pane">
            <div class="mail-pane-head">
              <div>
                <span class="adm-card-kicker">EDITOR</span>
                <h3>邮件模板</h3>
              </div>
              <button class="btn-text" type="button" @click="resetCurrentTemplate">恢复默认</button>
            </div>
            <div class="mail-template-tabs" role="tablist" aria-label="邮件模板类型">
              <button
                v-for="item in mailTemplateKinds"
                :key="item.key"
                type="button"
                :class="{ on: templateKind === item.key }"
                @click="templateKind = item.key"
              >{{ item.label }}</button>
            </div>
            <div class="field">
              <label :for="`page-subject-${templateKind}`">邮件主题</label>
              <input :id="`page-subject-${templateKind}`" v-model="smtpDraft[currentTemplate.subjectKey]" type="text" placeholder="邮件主题" />
            </div>
            <div class="mail-editor-toolbar">
              <span>插入变量</span>
              <button v-for="name in templatePlaceholders" :key="name" type="button" @click="insertPlaceholder(name)">{{ name }}</button>
            </div>
            <div class="field mail-html-field">
              <label :for="`page-html-${templateKind}`">HTML 源码</label>
              <textarea
                :id="`page-html-${templateKind}`"
                ref="htmlEditor"
                v-model="smtpDraft[currentTemplate.htmlKey]"
                spellcheck="false"
                placeholder="在这里编辑 HTML 邮件模板"
              ></textarea>
            </div>
          </section>

          <section class="mail-preview-pane">
            <div class="mail-pane-head">
              <div>
                <span class="adm-card-kicker">LIVE PREVIEW</span>
                <h3>实时预览</h3>
              </div>
              <span class="mail-preview-badge">示例数据</span>
            </div>
            <div class="mail-preview-subject">
              <span>主题</span>
              <strong>{{ previewSubject || '未填写主题' }}</strong>
            </div>
            <div class="mail-preview-frame">
              <iframe :srcdoc="previewHtml" title="邮件模板预览" sandbox=""></iframe>
            </div>
            <p class="mail-preview-note">预览使用示例变量；实际发送时会替换为站点名、验证码或重置链接。</p>
          </section>
        </div>

        <div class="smtp-page-save">
          <span class="meta">修改先保存在页面草稿中，点击保存后才会生效。</span>
          <button class="btn" type="button" :disabled="savingSmtp" @click="saveSmtpTemplates">
            {{ savingSmtp ? '保存中…' : '保存邮件模板' }}
          </button>
        </div>
      </div>

      <div v-else-if="activeTab === 'update'" class="adm-update-wrap">
        <OtaPanel />
      </div>

      <div v-else-if="activeTab === 'ai'" class="ai-studio">
        <section class="ai-hero">
          <div class="ai-hero-main">
            <span class="ai-orb" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6 2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" />
                <circle cx="12" cy="12" r="3.2" />
              </svg>
            </span>
            <div class="ai-hero-copy">
              <span class="adm-card-kicker">AI WORKSPACE</span>
              <h1>AI 设置</h1>
              <p>连接一个 OpenAI 兼容服务，并在写作、审稿、评论与摘要环节按需启用。</p>
            </div>
          </div>
          <div class="ai-hero-side">
            <span class="adm-status" :class="boolValue(config.aiEnabled) ? 'is-published' : 'is-draft'">{{ boolValue(config.aiEnabled) ? 'AI 已启用' : 'AI 未启用' }}</span>
            <div class="ai-hero-actions">
              <button class="btn btn-ghost" type="button" :disabled="testingAi" @click="testAi">{{ testingAi ? '测试中…' : '测试连接' }}</button>
              <button class="btn" type="button" @click="aiOpen = true">配置 AI</button>
            </div>
          </div>
          <div v-if="aiTestResult" class="ai-test-banner">
            <span class="ai-test-dot" aria-hidden="true"></span>
            <span>{{ aiTestResult }}</span>
          </div>
        </section>

        <section class="adm-card ai-capability-panel">
          <div class="adm-card-head">
            <div><span class="adm-card-kicker">CAPABILITY</span><h2>能力与提示词</h2><p class="ai-card-sub">按需开启能力，并为每种能力维护独立提示词。</p></div>
            <span class="ai-capability-count">{{ aiFeatures.filter((f) => boolValue(config[f.enabledKey])).length }} / {{ aiFeatures.length }} 已开启</span>
          </div>
          <div class="ai-feature-grid">
            <article v-for="feature in aiFeatures" :key="feature.key" class="ai-feature-card" :class="{ off: !boolValue(config[feature.enabledKey]) }">
              <div class="ai-feature-top">
                <span class="ai-feature-icon" aria-hidden="true">
                  <svg v-if="feature.key === 'article'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M5 3h10l4 4v14H5V3Z"/><path d="M15 3v4h4M8 12h8M8 16h6"/></svg>
                  <svg v-else-if="feature.key === 'comment'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 5h16v11H9l-5 4V5Z"/><path d="M8 9h8M8 12h5"/></svg>
                  <svg v-else-if="feature.key === 'summary'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 6h16M4 12h16M4 18h10"/></svg>
                  <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 10v4M7 8v8M10 6v12M14 9v6M17 7v10M20 10v4"/></svg>
                </span>
                <span class="ai-feature-state">{{ boolValue(config[feature.enabledKey]) ? '已开启' : '已关闭' }}</span>
              </div>
              <b>{{ feature.title }}</b>
              <p>{{ feature.desc }}</p>
              <div class="ai-feature-foot">
                <label class="ai-inline-switch">
                  <span>{{ boolValue(config[feature.enabledKey]) ? '启用中' : '已停用' }}</span>
                  <input
                    type="checkbox"
                    :checked="boolValue(config[feature.enabledKey])"
                    :disabled="featureSaving === feature.enabledKey"
                    @change="toggleAiFeature(feature, $event)"
                  />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <button class="btn-text ai-prompt-btn" type="button" @click="openPrompt(feature.key)">提示词 →</button>
              </div>
            </article>
          </div>
          <p class="adm-note">开关与提示词保存在数据库；关闭某项能力后，对应的 AI 操作会直接拒绝。</p>
        </section>
      </div>

      <div v-else-if="activeTab === 'registration'" class="adm-settings-grid">
        <div class="adm-main-stack">
          <section class="adm-card">
            <div class="adm-card-head">
              <div><span class="adm-card-kicker">REGISTRATION</span><h2>注册策略</h2></div>
              <span class="adm-status" :class="boolValue(config.registrationEnabled) ? 'is-published' : 'is-draft'">{{ boolValue(config.registrationEnabled) ? '已开放' : '已关闭' }}</span>
            </div>
            <div class="form-grid">
              <div class="field span-2">
                <label class="adm-switch-label">
                  <span>开放新用户注册</span>
                  <input type="checkbox" :checked="boolValue(config.registrationEnabled)" @change="setBool('registrationEnabled', $event)" />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <p class="adm-note">关闭后注册页显示自定义提示，已有账号不受影响。</p>
              </div>
              <div class="field">
                <label class="adm-switch-label">
                  <span>注册邮箱验证</span>
                  <input type="checkbox" :checked="boolValue(config.registrationVerifyEmail)" @change="setBool('registrationVerifyEmail', $event)" />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <p class="adm-note">要求填写邮箱验证码；依赖 SMTP。</p>
              </div>
              <div class="field">
                <label class="adm-switch-label">
                  <span>允许找回密码</span>
                  <input type="checkbox" :checked="boolValue(config.passwordResetEnabled)" @change="setBool('passwordResetEnabled', $event)" />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <p class="adm-note">关闭后登录页的找回密码入口停用。</p>
              </div>
              <div class="field">
                <label for="registrationMinPasswordLength">密码最小长度</label>
                <input id="registrationMinPasswordLength" v-model="config.registrationMinPasswordLength" type="number" min="6" max="64" />
                <p class="adm-note">6–64 位，同时作用于注册、改密和重置密码。</p>
              </div>
              <div class="field">
                <label for="registrationMaxUsers">用户总数上限</label>
                <input id="registrationMaxUsers" v-model="config.registrationMaxUsers" type="number" min="0" />
                <p class="adm-note">填 0 表示不限制；达到上限后注册会被拒绝。</p>
              </div>
              <div class="field span-2">
                <label class="adm-switch-label">
                  <span>注册需要邀请码</span>
                  <input type="checkbox" :checked="boolValue(config.registrationInviteEnabled)" @change="setBool('registrationInviteEnabled', $event)" />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <p class="adm-note">邀请码只在服务端校验，不会暴露给公共页面。</p>
              </div>
              <div v-if="boolValue(config.registrationInviteEnabled)" class="field span-2">
                <label for="registrationInviteCode">邀请码</label>
                <input id="registrationInviteCode" v-model="config.registrationInviteCode" type="text" maxlength="128" placeholder="例如：DE EK-2026" />
              </div>
              <div class="field span-2">
                <label for="registrationEmailAllowlist">允许注册的邮箱域名</label>
                <textarea id="registrationEmailAllowlist" v-model="config.registrationEmailAllowlist" rows="2" maxlength="4000" placeholder="留空不限制；每行或用逗号分隔，例如 example.com、*.company.com"></textarea>
                <p class="adm-note">支持精确域名和通配后缀；留空表示所有邮箱域名均可注册。</p>
              </div>
              <div class="field">
                <label class="adm-switch-label">
                  <span>新用户需要审核</span>
                  <input type="checkbox" :checked="boolValue(config.registrationApproval)" @change="setBool('registrationApproval', $event)" />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <p class="adm-note">开启后新账号默认禁用，需在用户管理中手动启用。</p>
              </div>
              <div class="field">
                <label class="adm-switch-label">
                  <span>注册后自动登录</span>
                  <input type="checkbox" :checked="boolValue(config.registrationAutoLogin)" :disabled="boolValue(config.registrationApproval)" @change="setBool('registrationAutoLogin', $event)" />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <p class="adm-note">关闭后注册成功只返回账号信息，用户需自行登录。审核模式下不签发登录凭证。</p>
              </div>
              <div class="field span-2">
                <label for="registrationNotice">注册页公告</label>
                <textarea id="registrationNotice" v-model="config.registrationNotice" rows="3" maxlength="4000" placeholder="例如：本站账号仅用于评论与收藏，请勿发布广告。"></textarea>
              </div>
              <div class="field">
                <label for="registrationSuccessMessage">注册成功提示</label>
                <textarea id="registrationSuccessMessage" v-model="config.registrationSuccessMessage" rows="2" maxlength="1000" placeholder="留空使用默认欢迎语"></textarea>
              </div>
              <div class="field">
                <label for="registrationDefaultBio">新用户默认简介</label>
                <textarea id="registrationDefaultBio" v-model="config.registrationDefaultBio" rows="2" maxlength="512" placeholder="可留空"></textarea>
              </div>
              <div class="field span-2">
                <label for="registrationClosedMessage">关闭注册时的提示</label>
                <textarea id="registrationClosedMessage" v-model="config.registrationClosedMessage" rows="2" maxlength="1000"></textarea>
              </div>
            </div>
            <div class="adm-form-actions">
              <button class="btn" type="button" :disabled="savingRegistration" @click="saveRegistration">{{ savingRegistration ? '保存中…' : '保存注册设置' }}</button>
            </div>
          </section>
        </div>
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">FLOW</span><h2>注册流程</h2></div></div>
            <ol class="adm-flow-list">
              <li>填写邮箱；邮箱即用户名</li>
              <li>完成本地图形验证码</li>
              <li v-if="boolValue(config.registrationVerifyEmail)">获取并填写邮箱验证码</li>
              <li v-if="boolValue(config.registrationInviteEnabled)">填写站长提供的邀请码</li>
              <li v-if="boolValue(config.registrationApproval)">提交后进入待审核状态</li>
              <li v-else-if="boolValue(config.registrationAutoLogin)">注册成功后自动登录</li>
              <li v-else>注册成功后前往登录页</li>
            </ol>
          </section>
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">DEPENDENCY</span><h2>依赖检查</h2></div></div>
            <dl class="adm-facts">
              <div><dt>SMTP</dt><dd>{{ boolValue(config.smtpEnabled) ? '已启用' : '未启用' }}</dd></div>
              <div><dt>注册</dt><dd>{{ boolValue(config.registrationEnabled) ? '开放' : '关闭' }}</dd></div>
              <div><dt>邮箱验证</dt><dd>{{ boolValue(config.registrationVerifyEmail) ? '开启' : '关闭' }}</dd></div>
              <div><dt>找回密码</dt><dd>{{ boolValue(config.passwordResetEnabled) && boolValue(config.registrationVerifyEmail) ? '可用' : '停用' }}</dd></div>
              <div><dt>邀请码</dt><dd>{{ boolValue(config.registrationInviteEnabled) ? (config.registrationInviteCode ? '已配置' : '缺少邀请码') : '不需要' }}</dd></div>
              <div><dt>用户上限</dt><dd>{{ Number(config.registrationMaxUsers || 0) > 0 ? `最多 ${config.registrationMaxUsers} 人` : '不限制' }}</dd></div>
            </dl>
            <p v-if="boolValue(config.registrationEnabled) && boolValue(config.registrationVerifyEmail) && !boolValue(config.smtpEnabled)" class="adm-note danger-text">注册已开放且要求邮箱验证，但 SMTP 未启用，验证码将无法发送。请先配置 SMTP 或关闭邮箱验证。</p>
            <p v-if="boolValue(config.registrationInviteEnabled) && !config.registrationInviteCode" class="adm-note danger-text">已开启邀请码但未填写内容，注册会被服务端拒绝。</p>
          </section>
        </aside>
      </div>

      <div v-else class="adm-settings-grid">
        <div class="adm-main-stack">
          <section class="adm-card">
            <div class="adm-card-head">
              <div><span class="adm-card-kicker">SITE MODE</span><h2>站点模式</h2></div>
              <div class="maint-status-pills">
                <span class="adm-status" :class="boolValue(config.maintenanceEnabled) ? 'is-draft' : 'is-published'">{{ boolValue(config.maintenanceEnabled) ? '维护中' : '正常' }}</span>
                <span class="adm-status" :class="boolValue(config.readOnlyMode) ? 'is-draft' : 'is-published'">{{ boolValue(config.readOnlyMode) ? '只读' : '可写' }}</span>
              </div>
            </div>
            <div class="form-grid">
              <div class="field span-2">
                <label class="adm-switch-label">
                  <span>开启维护模式</span>
                  <input type="checkbox" :checked="boolValue(config.maintenanceEnabled)" @change="setBool('maintenanceEnabled', $event)" />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <p class="adm-note">除后台、登录和站点元信息外，所有 API 会返回 503；公共站会显示维护遮罩。</p>
                <textarea v-model="config.maintenanceMessage" rows="2" maxlength="2000" placeholder="维护期间展示给访客的提示"></textarea>
              </div>
              <div class="field span-2">
                <label class="adm-switch-label">
                  <span>开启只读模式</span>
                  <input type="checkbox" :checked="boolValue(config.readOnlyMode)" @change="setBool('readOnlyMode', $event)" />
                  <i class="adm-switch" aria-hidden="true"></i>
                </label>
                <p class="adm-note">访客可以浏览，但发布、评论、点赞、收藏、改资料和上传都会被服务端拒绝。管理员后台不受影响。</p>
                <textarea v-model="config.readOnlyMessage" rows="2" maxlength="2000" placeholder="只读期间展示给访客的提示"></textarea>
              </div>
            </div>
            <div class="adm-form-actions">
              <button class="btn" type="button" :disabled="savingMaintenance" @click="saveMaintenance">{{ savingMaintenance ? '保存中…' : '保存站点模式' }}</button>
            </div>
          </section>

          <section class="adm-card">
            <div class="adm-card-head">
              <div><span class="adm-card-kicker">DATA</span><h2>运行与数据</h2></div>
              <button class="btn-text" type="button" @click="loadMaintenanceOverview">刷新 →</button>
            </div>
            <div class="adm-runtime-grid">
              <div class="adm-runtime-item"><span>文章</span><strong>{{ overview?.posts ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>已发布</span><strong>{{ overview?.publishedPosts ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>草稿</span><strong>{{ overview?.draftPosts ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>待审文章</span><strong>{{ overview?.pendingPosts ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>评论</span><strong>{{ overview?.comments ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>待审评论</span><strong>{{ overview?.pendingComments ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>用户</span><strong>{{ overview?.users ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>禁用用户</span><strong>{{ overview?.disabledUsers ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>标签</span><strong>{{ overview?.tags ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>文件</span><strong>{{ overview?.files ?? '—' }}</strong></div>
              <div class="adm-runtime-item"><span>堆内存</span><strong>{{ formatBytes(overview?.heapUsed) }} / {{ formatBytes(overview?.heapMax) }}</strong></div>
              <div class="adm-runtime-item"><span>运行时长</span><strong>{{ durationText(overview?.uptimeSeconds) }}</strong></div>
            </div>
            <div class="maint-action-row">
              <div>
                <strong>导出站点数据</strong>
                <p class="adm-note">生成 JSON 快照，包含文章、评论、标签和脱敏用户资料；不含密码哈希与密钥。</p>
              </div>
              <button class="btn btn-ghost" type="button" :disabled="exporting" @click="exportData">{{ exporting ? '导出中…' : '导出数据' }}</button>
            </div>
            <div class="maint-action-row">
              <div>
                <strong>清理更新缓存</strong>
                <p class="adm-note">清理 GitHub Release 缓存、已下载的更新包与更新历史；下载任务进行中会拒绝执行。</p>
              </div>
              <button class="btn btn-ghost" type="button" :disabled="clearingCache" @click="clearOtaCache">{{ clearingCache ? '清理中…' : '清理缓存' }}</button>
            </div>
          </section>

          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">RUNTIME</span><h2>当前进程</h2></div><span class="badge" :class="ota?.canAutoUpdate ? 'on' : 'warn'">{{ otaModeLabel(ota?.mode) }}</span></div>
            <div class="adm-runtime-grid">
              <div class="adm-runtime-item"><span>版本</span><strong>{{ version?.version || ota?.version || '—' }}</strong></div>
              <div class="adm-runtime-item"><span>构建时间</span><strong>{{ version?.buildTime || '—' }}</strong></div>
              <div class="adm-runtime-item"><span>已运行</span><strong>{{ ota?.uptimeSeconds ?? version?.uptimeSeconds ?? '—' }} 秒</strong></div>
              <div class="adm-runtime-item"><span>实例</span><strong class="mono">{{ shortSha(ota?.instanceId || version?.instanceId) }}</strong></div>
              <div class="adm-runtime-item"><span>jar 大小</span><strong>{{ formatBytes(ota?.size) }}</strong></div>
              <div class="adm-runtime-item"><span>PID</span><strong>{{ ota?.pid || version?.pid || '—' }}</strong></div>
              <div class="adm-runtime-item"><span>数据库</span><strong>{{ health?.database || '—' }}</strong></div>
              <div class="adm-runtime-item"><span>Redis</span><strong>{{ health?.redis || '—' }}</strong></div>
            </div>
            <div class="ota-sha"><span>当前 SHA-256</span><code>{{ shortSha(ota?.sha256) }}</code></div>
            <div class="adm-restart-panel">
              <div>
                <strong>重启站点</strong>
                <p class="adm-note">按当前 jar 重启，不更换版本；重启期间会短暂断站。未启用自动更新时不可用。</p>
              </div>
              <button class="btn btn-danger" type="button" :disabled="restarting || !ota?.canAutoUpdate" @click="restartSite">{{ restarting ? '重启中…' : '重启站点' }}</button>
            </div>
            <div v-if="restarting || restartMessage" class="adm-restart-status">
              <span class="ota-spinner" v-if="restarting"></span>
              <span>{{ restartMessage }}</span>
            </div>
            <div v-if="!ota?.canAutoUpdate" class="adm-note">{{ ota?.modeNote || '当前运行模式不支持自动重启。' }}</div>
          </section>
        </div>

        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">HEALTH</span><h2>健康概览</h2></div></div>
            <dl class="adm-facts">
              <div><dt>磁盘</dt><dd>{{ formatBytes(overview?.diskUsable) }} 可用 / {{ formatBytes(overview?.diskTotal) }}</dd></div>
              <div><dt>处理器</dt><dd>{{ overview?.processors ?? '—' }} 核</dd></div>
              <div><dt>Java</dt><dd>{{ overview?.javaVersion || '—' }}</dd></div>
              <div><dt>系统</dt><dd class="adm-break">{{ overview?.os || '—' }}</dd></div>
              <div><dt>工作目录</dt><dd class="adm-break">{{ overview?.workDir || '—' }}</dd></div>
            </dl>
            <button class="btn btn-ghost btn-sm" type="button" @click="loadRuntime">重新读取</button>
          </section>
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">SYSTEM</span><h2>运行信息</h2></div></div>
            <dl class="adm-facts">
              <div><dt>版本</dt><dd>{{ version?.version || '—' }} <button class="btn-text" type="button" @click="copyVersion">复制</button></dd></div>
              <div><dt>构建</dt><dd class="adm-break">{{ ota?.jarPath || '—' }}</dd></div>
              <div><dt>Java</dt><dd>{{ version?.java || '—' }}</dd></div>
              <div><dt>系统</dt><dd>{{ version?.os || '—' }}</dd></div>
            </dl>
          </section>
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">NOTE</span><h2>模式说明</h2></div></div>
            <p class="adm-note">维护模式和只读模式都由后端过滤器强制执行，不是前端遮罩。后台管理员始终可以进入设置页关闭模式。</p>
            <p class="adm-note">导出数据只做快照备份，不会自动清空或覆盖现有数据。</p>
          </section>
        </aside>
      </div>
    </template>

    <div v-if="smtpOpen" class="adm-modal-wrap mail-modal-wrap" @click.self="smtpOpen = false">
      <div class="adm-modal mail-modal">
        <div class="mail-modal-head">
          <div class="mail-modal-heading">
            <span class="mail-modal-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                <rect x="3" y="5" width="18" height="14" rx="2" />
                <path d="m3.5 7 8.5 6 8.5-6" />
              </svg>
            </span>
            <div>
              <span class="adm-card-kicker">SMTP CONNECTION</span>
              <h2>配置邮箱发送</h2>
              <p>登录账号即发件地址；发件人名称只用于收件箱里的称呼展示。</p>
            </div>
          </div>
          <span class="adm-status" :class="boolValue(smtpDraft.smtpEnabled) ? 'is-published' : 'is-draft'">
            {{ boolValue(smtpDraft.smtpEnabled) ? '已启用' : '未启用' }}
          </span>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="smtpOpen = false">×</button>
        </div>

        <div class="smtp-config-layout">
          <section class="smtp-config-panel">
            <div class="smtp-panel-head">
              <span class="smtp-step">01</span>
              <div>
                <h3>发信服务器</h3>
                <p>填写邮箱服务商提供的 SMTP 地址与登录凭据。</p>
              </div>
              <label class="adm-switch-label compact" title="启用 SMTP 邮件">
                <span class="sr-only">启用 SMTP 邮件</span>
                <input type="checkbox" :checked="boolValue(smtpDraft.smtpEnabled)" @change="setDraftBool('smtpEnabled', $event)" />
                <i class="adm-switch" aria-hidden="true"></i>
              </label>
            </div>
            <div class="smtp-form-grid">
              <div class="field smtp-host-field">
                <label for="smtpHost">SMTP 主机</label>
                <input id="smtpHost" v-model="smtpDraft.smtpHost" type="text" placeholder="smtp.example.com" autocomplete="off" />
              </div>
              <div class="field">
                <label for="smtpPort">端口</label>
                <input id="smtpPort" v-model="smtpDraft.smtpPort" type="number" placeholder="465" />
              </div>
              <div class="field span-2">
                <label for="smtpUsername">登录账号（发件地址）</label>
                <input id="smtpUsername" v-model="smtpDraft.smtpUsername" type="email" placeholder="you@example.com" autocomplete="username" />
                <p class="smtp-field-help">这里填完整邮箱地址；系统会把它直接作为 From 地址。</p>
              </div>
              <div class="field span-2">
                <label for="smtpPassword">授权码 / 密码</label>
                <input id="smtpPassword" v-model="smtpDraft.smtpPassword" type="password" :placeholder="config.smtpPasswordSet === 'true' ? '已设置，留空保持不变' : '请输入 SMTP 密码或授权码'" autocomplete="new-password" />
              </div>
              <div class="field span-2">
                <label>加密方式</label>
                <div class="smtp-security-row">
                  <label class="smtp-security-option" :class="{ on: boolValue(smtpDraft.smtpSsl) }">
                    <input type="checkbox" :checked="boolValue(smtpDraft.smtpSsl)" @change="setDraftBool('smtpSsl', $event)" />
                    <span class="smtp-security-check" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m5 12 4 4 10-9" /></svg>
                    </span>
                    <span><b>SSL</b><small>常用 465 端口</small></span>
                  </label>
                  <label class="smtp-security-option" :class="{ on: boolValue(smtpDraft.smtpStarttls) }">
                    <input type="checkbox" :checked="boolValue(smtpDraft.smtpStarttls)" @change="setDraftBool('smtpStarttls', $event)" />
                    <span class="smtp-security-check" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m5 12 4 4 10-9" /></svg>
                    </span>
                    <span><b>STARTTLS</b><small>常用 587 端口</small></span>
                  </label>
                </div>
              </div>
            </div>
          </section>

          <section class="smtp-config-panel smtp-identity-panel">
            <div class="smtp-panel-head">
              <span class="smtp-step">02</span>
              <div>
                <h3>发件身份</h3>
                <p>决定读者在收件箱里看到的名字。</p>
              </div>
            </div>
            <div class="field">
              <label for="smtpSenderName">发件人名称</label>
              <input id="smtpSenderName" v-model="smtpDraft.smtpSenderName" type="text" maxlength="60" placeholder="留空则使用站点名" />
              <p class="smtp-field-help">只影响显示名称，不会修改实际发件邮箱。</p>
            </div>
            <div class="smtp-sender-preview">
              <span>读者将看到</span>
              <div class="smtp-sender-card">
                <span class="smtp-sender-avatar">{{ (smtpDraft.smtpSenderName || config.siteName || '墨').slice(0, 1) }}</span>
                <div>
                  <b>{{ smtpDraft.smtpSenderName || config.siteName || '站点名称' }}</b>
                  <small>{{ smtpDraft.smtpUsername || '尚未填写登录账号' }}</small>
                </div>
              </div>
            </div>
            <p class="smtp-identity-note">登录账号必须是邮箱服务商允许发信的完整地址；部分服务商要求它与登录账号完全一致。</p>
            <div class="smtp-provider-hints">
              <span>常用服务商</span>
              <div><b>QQ 邮箱</b><small>smtp.qq.com · 465 · SSL</small></div>
              <div><b>163 邮箱</b><small>smtp.163.com · 465 · SSL</small></div>
              <div><b>Gmail</b><small>smtp.gmail.com · 465 · SSL</small></div>
            </div>
          </section>
        </div>

        <div class="mail-modal-foot">
          <div class="mail-test-box">
            <div class="field">
              <label for="modalMailTo">测试收件邮箱</label>
              <input id="modalMailTo" v-model="mailTo" type="email" placeholder="you@example.com" />
            </div>
            <button class="btn btn-ghost btn-sm" type="button" :disabled="testingMail" @click="testMail">
              {{ testingMail ? '发送中…' : '保存并发送测试' }}
            </button>
          </div>
          <div class="adm-modal-actions">
            <button class="btn btn-ghost" type="button" @click="smtpOpen = false">取消</button>
            <button class="btn" type="button" :disabled="savingSmtp" @click="saveSmtp">{{ savingSmtp ? '保存中…' : '保存设置' }}</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="aiOpen" class="adm-modal-wrap" @click.self="aiOpen = false">
      <div class="adm-modal is-wide">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI</span><h2>配置 AI</h2><p class="meta">使用 OpenAI 兼容接口；模型名称旁可获取服务端可用模型。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="aiOpen = false">×</button>
        </div>
        <div class="form-grid">
          <div class="field span-2">
            <label class="adm-switch-label">
              <span>启用 AI 辅助</span>
              <input type="checkbox" :checked="boolValue(config.aiEnabled)" @change="setBool('aiEnabled', $event)" />
              <i class="adm-switch" aria-hidden="true"></i>
            </label>
          </div>
          <div class="field">
            <label for="aiProvider">服务商名称</label>
            <select id="aiProvider" v-model="config.aiProvider">
              <option>OpenAI 兼容</option>
              <option>DeepSeek</option>
              <option>Moonshot</option>
              <option>自定义</option>
            </select>
          </div>
          <div class="field">
            <label for="aiModel">模型名称</label>
            <div class="adm-input-action">
              <input id="aiModel" v-model="config.aiModel" type="text" placeholder="例如：gpt-4o-mini / deepseek-chat" />
              <button class="btn btn-ghost btn-sm" type="button" :disabled="loadingModels" @click="fetchModels">{{ loadingModels ? '获取中…' : '获取模型' }}</button>
            </div>
          </div>
          <div class="field span-2">
            <label for="aiBaseUrl">Base URL</label>
            <input id="aiBaseUrl" v-model="config.aiBaseUrl" type="text" placeholder="https://api.openai.com/v1" />
            <p class="meta">填写到版本号为止，不要带 /chat/completions；系统会自动补全接口路径。</p>
          </div>
          <div class="field span-2">
            <label for="aiApiKey">API Key</label>
            <input id="aiApiKey" v-model="config.aiApiKey" type="password" :placeholder="config.aiApiKeySet === 'true' ? '已设置，留空保持不变' : 'sk-…'" autocomplete="new-password" />
          </div>
          <div v-if="modelError" class="field span-2"><p class="danger-text">{{ modelError }}</p></div>
          <div v-if="aiModels.length" class="field span-2">
            <label>可用模型（点击填入）</label>
            <div class="adm-model-list">
              <button v-for="m in aiModels" :key="m.id" type="button" class="adm-model-chip" :class="{ on: config.aiModel === m.id }" @click="applyModel(m.id)">{{ m.id }}</button>
            </div>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="aiOpen = false">取消</button>
          <button class="btn" type="button" :disabled="savingAi" @click="saveAi">{{ savingAi ? '保存中…' : '保存 AI 设置' }}</button>
        </div>
      </div>
    </div>
    <div v-if="promptOpen" class="adm-modal-wrap" @click.self="promptOpen = false">
      <div class="adm-modal is-wide">
        <div class="adm-modal-head">
          <div>
            <span class="adm-card-kicker">AI PROMPT</span>
            <h2>{{ promptFeatureTitle }}</h2>
            <p class="meta">提示词保存到数据库，后续对应 AI 操作会直接读取。可恢复系统默认后重新修改。</p>
          </div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="promptOpen = false">×</button>
        </div>
        <div class="field" v-for="field in promptFields" :key="field.key">
          <label :for="field.key">{{ field.label }}</label>
          <textarea :id="field.key" v-model="promptDraft[field.key]" maxlength="4000" style="min-height: 120px"></textarea>
        </div>
        <div class="adm-modal-actions split">
          <button class="btn btn-ghost" type="button" @click="resetPrompt">恢复默认提示词</button>
          <div>
            <button class="btn btn-ghost" type="button" @click="promptOpen = false">取消</button>
            <button class="btn" type="button" :disabled="promptSaving" @click="savePrompt">{{ promptSaving ? '保存中…' : '保存提示词' }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.adm-switch-label {
  position: relative;
}

.adm-switch-label input {
  width: 1px;
  height: 1px;
  overflow: hidden;
}

.adm-main-stack {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

.maint-status-pills {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.maint-action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 16px 0;
  border-top: 1px solid var(--adm-line);
}

.maint-action-row:first-of-type {
  border-top: 0;
}

.maint-action-row strong {
  display: block;
  margin-bottom: 4px;
}

.maint-action-row .adm-note {
  margin: 0;
}

.maint-action-row .btn {
  flex: none;
}

@media (max-width: 720px) {
  .maint-action-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
}
</style>
