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
const testingMail = ref(false)
const testingAi = ref(false)
const mailTo = ref('')
const smtpDraft = ref({})
const smtpDraftReady = ref(false)
const mailTemplateDefaults = ref({})
const templateKind = ref('registration')
const htmlEditor = ref(null)
const aiStatus = ref(null)
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
  aiCommentSummaryPrompt: '',
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
  'smtpFrom', 'smtpSsl', 'smtpStarttls',
  'mailTemplateRegistrationSubject', 'mailTemplateRegistrationHtml',
  'mailTemplateResetSubject', 'mailTemplateResetHtml',
  'mailTemplateTestSubject', 'mailTemplateTestHtml'
]

const currentTemplate = computed(() =>
  mailTemplateKinds.find((item) => item.key === templateKind.value) || mailTemplateKinds[0]
)

const previewVars = computed(() => ({
  siteName: config.value.siteName || '墨记',
  siteUrl: 'https://your-blog.example.com',
  year: String(new Date().getFullYear()),
  code: '836214',
  expireMinutes: '10',
  link: 'https://your-blog.example.com/reset-password?token=demo-token',
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
    desc: '审核评论、总结评论区'
  },
  {
    key: 'summary',
    enabledKey: 'aiSummaryEnabled',
    title: 'AI 摘要',
    desc: '编辑器内生成文章摘要'
  }
]

const promptFields = computed(() => ({
  article: [
    { key: 'aiArticleGeneratePrompt', label: 'AI 生成文章提示词' },
    { key: 'aiArticleReviewPrompt', label: 'AI 审核文章提示词' }
  ],
  comment: [
    { key: 'aiCommentReviewPrompt', label: 'AI 审核评论提示词' },
    { key: 'aiCommentSummaryPrompt', label: 'AI 总结评论提示词' }
  ],
  summary: [
    { key: 'aiSummaryPrompt', label: 'AI 摘要提示词' }
  ]
}[promptFeature.value] || []))

const promptFeatureTitle = computed(() => ({
  article: 'AI 文章提示词',
  comment: 'AI 评论提示词',
  summary: 'AI 摘要提示词'
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

async function loadAiStatus() {
  try {
    aiStatus.value = (await http.get('/admin/ai/status')).data
  } catch (e) {
    /* AI 状态读取失败不阻塞 */
  }
}

async function saveAi() {
  savingAi.value = true
  try {
    await persistConfig()
    await loadAiStatus()
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
    await loadAiStatus()
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
    await persistConfig()
    toast('注册设置已保存')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingRegistration.value = false
  }
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
  await Promise.all([loadConfig(), loadAiStatus(), loadRuntime(), loadMailTemplateDefaults()])
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

      <div v-else-if="activeTab === 'ai'">
        <div class="adm-settings-grid">
          <section class="adm-card">
            <div class="adm-card-head">
              <div><span class="adm-card-kicker">AI</span><h2>AI 设置</h2></div>
              <span class="adm-status" :class="boolValue(config.aiEnabled) ? 'is-published' : 'is-draft'">{{ boolValue(config.aiEnabled) ? '已启用' : '未启用' }}</span>
            </div>
            <dl class="adm-facts">
              <div><dt>服务商</dt><dd>{{ config.aiProvider || '—' }}</dd></div>
              <div><dt>模型</dt><dd>{{ config.aiModel || '—' }}</dd></div>
              <div><dt>Base URL</dt><dd class="adm-break">{{ config.aiBaseUrl || '—' }}</dd></div>
              <div><dt>密钥</dt><dd>{{ config.aiApiKeySet === 'true' ? '已设置' : '未设置' }}</dd></div>
              <div><dt>测试状态</dt><dd>{{ aiStatus?.message || (aiStatus?.enabled ? '已启用' : '未启用') }}</dd></div>
            </dl>
            <div class="adm-form-actions">
              <button class="btn" type="button" @click="aiOpen = true">配置 AI</button>
              <button class="btn btn-ghost" type="button" :disabled="testingAi" @click="testAi">{{ testingAi ? '测试中…' : '测试连接' }}</button>
              <span class="adm-note">模型名称旁可一键从 API 获取可用模型。</span>
            </div>
            <p v-if="aiTestResult" class="adm-test-result">{{ aiTestResult }}</p>
          </section>
          <aside class="adm-side-stack">
            <section class="adm-card">
              <div class="adm-card-head"><div><span class="adm-card-kicker">STATUS</span><h2>当前 AI 状态</h2></div></div>
              <dl class="adm-facts">
                <div><dt>启用</dt><dd>{{ aiStatus?.enabled ? '是' : '否' }}</dd></div>
                <div><dt>服务商</dt><dd>{{ aiStatus?.provider || '—' }}</dd></div>
                <div><dt>模型</dt><dd>{{ aiStatus?.model || '—' }}</dd></div>
                <div><dt>密钥</dt><dd>{{ aiStatus?.apiKeySet ? '已设置' : '未设置' }}</dd></div>
                <div><dt>Base URL</dt><dd class="adm-break">{{ aiStatus?.baseUrl || '—' }}</dd></div>
              </dl>
            </section>
          </aside>
        </div>
        <section class="adm-card adm-ai-cap-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">CAPABILITY</span><h2>AI 功能开关</h2></div></div>
          <div class="adm-ai-feature-list">
            <div v-for="feature in aiFeatures" :key="feature.key" class="adm-ai-feature" :class="{ off: !boolValue(config[feature.enabledKey]) }">
              <div class="adm-ai-feature-copy">
                <b>{{ feature.title }}</b>
                <span>{{ feature.desc }}</span>
              </div>
              <label class="adm-switch-label compact">
                <span class="sr-only">{{ feature.title }}开关</span>
                <input
                  type="checkbox"
                  :checked="boolValue(config[feature.enabledKey])"
                  :disabled="featureSaving === feature.enabledKey"
                  @change="toggleAiFeature(feature, $event)"
                />
                <i class="adm-switch" aria-hidden="true"></i>
              </label>
              <button class="btn-text" type="button" @click="openPrompt(feature.key)">配置提示词</button>
            </div>
          </div>
          <p class="adm-note">开关和提示词都保存在数据库，由后端读取；关闭后对应 AI 操作会直接拒绝。</p>
        </section>
      </div>

      <div v-else-if="activeTab === 'registration'" class="adm-settings-grid">
        <section class="adm-card">
          <div class="adm-card-head">
            <div><span class="adm-card-kicker">REGISTRATION</span><h2>注册开关</h2></div>
            <span class="adm-status" :class="boolValue(config.registrationEnabled) ? 'is-published' : 'is-draft'">{{ boolValue(config.registrationEnabled) ? '已开放' : '已关闭' }}</span>
          </div>
          <div class="form-grid">
            <div class="field span-2">
              <label class="adm-switch-label">
                <span>开放新用户注册</span>
                <input type="checkbox" :checked="boolValue(config.registrationEnabled)" @change="setBool('registrationEnabled', $event)" />
                <i class="adm-switch" aria-hidden="true"></i>
              </label>
              <p class="adm-note">关闭后注册页会直接提示暂未开放，已有账号不受影响。</p>
            </div>
            <div class="field span-2">
              <label class="adm-switch-label">
                <span>开启邮箱验证（注册验证码 / 找回密码）</span>
                <input type="checkbox" :checked="boolValue(config.registrationVerifyEmail)" @change="setBool('registrationVerifyEmail', $event)" />
                <i class="adm-switch" aria-hidden="true"></i>
              </label>
              <p class="adm-note">开启后，注册必须获取并填写邮箱验证码，同时开放邮箱找回密码；需要可用的 SMTP 服务。关闭后注册只需邮箱、密码与本地图形验证码，找回密码入口会同步停用。</p>
            </div>
          </div>
          <div class="adm-form-actions">
            <button class="btn" type="button" :disabled="savingRegistration" @click="saveRegistration">{{ savingRegistration ? '保存中…' : '保存注册设置' }}</button>
          </div>
        </section>
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">FLOW</span><h2>注册流程</h2></div></div>
            <ol class="adm-flow-list">
              <li>填写邮箱；邮箱即用户名</li>
              <li>完成本地图形验证码</li>
              <li>按开关决定是否填写邮箱验证码</li>
              <li>填写密码，注册成功后自动登录</li>
            </ol>
          </section>
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">DEPENDENCY</span><h2>依赖检查</h2></div></div>
            <dl class="adm-facts">
              <div><dt>SMTP</dt><dd>{{ boolValue(config.smtpEnabled) ? '已启用' : '未启用' }}</dd></div>
              <div><dt>注册</dt><dd>{{ boolValue(config.registrationEnabled) ? '开放' : '关闭' }}</dd></div>
              <div><dt>邮箱验证</dt><dd>{{ boolValue(config.registrationVerifyEmail) ? '开启' : '关闭' }}</dd></div>
              <div><dt>找回密码</dt><dd>{{ boolValue(config.registrationVerifyEmail) ? '可用' : '停用' }}</dd></div>
            </dl>
            <p v-if="boolValue(config.registrationEnabled) && boolValue(config.registrationVerifyEmail) && !boolValue(config.smtpEnabled)" class="adm-note danger-text">注册已开放且要求邮箱验证，但 SMTP 未启用，验证码将无法发送。请先配置 SMTP 或关闭邮箱验证。</p>
          </section>
        </aside>
      </div>

      <div v-else class="adm-settings-grid">
        <section class="adm-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">RUNTIME</span><h2>当前运行</h2></div><span class="badge" :class="ota?.canAutoUpdate ? 'on' : 'warn'">{{ otaModeLabel(ota?.mode) }}</span></div>
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
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">SYSTEM</span><h2>运行信息</h2></div></div>
            <dl class="adm-facts">
              <div><dt>版本</dt><dd>{{ version?.version || '—' }} <button class="btn-text" type="button" @click="copyVersion">复制</button></dd></div>
              <div><dt>构建</dt><dd class="adm-break">{{ ota?.jarPath || '—' }}</dd></div>
              <div><dt>Java</dt><dd>{{ version?.java || '—' }}</dd></div>
              <div><dt>系统</dt><dd>{{ version?.os || '—' }}</dd></div>
            </dl>
            <button class="btn btn-ghost btn-sm" type="button" @click="loadRuntime">重新读取</button>
          </section>
        </aside>
      </div>
    </template>

    <div v-if="smtpOpen" class="adm-modal-wrap mail-modal-wrap" @click.self="smtpOpen = false">
      <div class="adm-modal mail-modal">
        <div class="adm-modal-head">
          <div>
            <span class="adm-card-kicker">SMTP SETTINGS</span>
            <h2>配置邮箱发送</h2>
            <p class="meta">只维护发信连接与测试邮件；HTML 模板和实时预览已移到 SMTP 页面。敏感密钥不会回显，留空表示沿用旧值。</p>
          </div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="smtpOpen = false">×</button>
        </div>

        <div class="mail-config-grid">
          <label class="mail-check mail-check-lead">
            <input type="checkbox" :checked="boolValue(smtpDraft.smtpEnabled)" @change="setDraftBool('smtpEnabled', $event)" />
            <span>启用 SMTP 邮件</span>
          </label>
          <div class="field">
            <label for="smtpHost">SMTP 主机</label>
            <input id="smtpHost" v-model="smtpDraft.smtpHost" type="text" placeholder="smtp.example.com" />
          </div>
          <div class="field">
            <label for="smtpPort">端口</label>
            <input id="smtpPort" v-model="smtpDraft.smtpPort" type="number" placeholder="465" />
          </div>
          <div class="field">
            <label for="smtpUsername">登录账号</label>
            <input id="smtpUsername" v-model="smtpDraft.smtpUsername" type="text" placeholder="you@example.com" />
          </div>
          <div class="field">
            <label for="smtpPassword">授权码 / 密码</label>
            <input id="smtpPassword" v-model="smtpDraft.smtpPassword" type="password" :placeholder="config.smtpPasswordSet === 'true' ? '已设置，留空保持不变' : '请输入 SMTP 密码或授权码'" autocomplete="new-password" />
          </div>
          <div class="field">
            <label for="smtpFrom">发件人地址</label>
            <input id="smtpFrom" v-model="smtpDraft.smtpFrom" type="email" placeholder="留空则使用登录账号" />
          </div>
          <div class="field">
            <label>加密方式</label>
            <div class="mail-check-row">
              <label class="mail-check"><input type="checkbox" :checked="boolValue(smtpDraft.smtpSsl)" @change="setDraftBool('smtpSsl', $event)" /><span>SSL</span></label>
              <label class="mail-check"><input type="checkbox" :checked="boolValue(smtpDraft.smtpStarttls)" @change="setDraftBool('smtpStarttls', $event)" /><span>STARTTLS</span></label>
            </div>
          </div>
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
