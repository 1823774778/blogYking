<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'
import { formatDateTime } from '../../utils'
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

function boolValue(value) {
  return String(value).toLowerCase() === 'true'
}

function setBool(key, event) {
  config.value[key] = event.target.checked ? 'true' : 'false'
  if (key === 'registrationEnabled' && event.target.checked) {
    config.value.registrationVerifyEmail = 'true'
  }
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

async function saveSmtp() {
  savingSmtp.value = true
  try {
    await persistConfig()
    smtpOpen.value = false
    toast('SMTP 设置已保存，立即生效')
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
    await http.post('/admin/mail/test', { to: mailTo.value.trim() })
    toast('测试邮件已发送，请检查收件箱')
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
    if (boolValue(config.value.registrationEnabled)) {
      config.value.registrationVerifyEmail = 'true'
    }
    await persistConfig()
    toast('注册设置已保存')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingRegistration.value = false
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
  await Promise.all([loadConfig(), loadAiStatus(), loadRuntime()])
})
</script>

<template>
  <div class="adm-page">
    <div v-if="loading" class="adm-loading">正在读取设置…</div>

    <template v-else>
      <div v-if="activeTab === 'smtp'" class="adm-settings-grid">
        <section class="adm-card">
          <div class="adm-card-head">
            <div><span class="adm-card-kicker">SMTP</span><h2>邮件发送</h2></div>
            <span class="adm-status" :class="boolValue(config.smtpEnabled) ? 'is-published' : 'is-draft'">{{ boolValue(config.smtpEnabled) ? '已启用' : '未启用' }}</span>
          </div>
          <dl class="adm-facts">
            <div><dt>SMTP 主机</dt><dd>{{ config.smtpHost || '—' }}</dd></div>
            <div><dt>端口 / 加密</dt><dd>{{ config.smtpPort || '—' }} · {{ boolValue(config.smtpSsl) ? 'SSL' : boolValue(config.smtpStarttls) ? 'STARTTLS' : '无' }}</dd></div>
            <div><dt>登录账号</dt><dd>{{ config.smtpUsername || '—' }}</dd></div>
            <div><dt>发件人</dt><dd>{{ config.smtpFrom || config.smtpUsername || '—' }}</dd></div>
            <div><dt>授权码</dt><dd>{{ config.smtpPasswordSet === 'true' ? '已设置' : '未设置' }}</dd></div>
          </dl>
          <div class="adm-form-actions">
            <button class="btn" type="button" @click="smtpOpen = true">配置 SMTP</button>
            <span class="adm-note">敏感密钥不会回显，弹窗中留空表示沿用旧值。</span>
          </div>
        </section>
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">TEST</span><h2>发送测试邮件</h2></div></div>
            <div class="field">
              <label for="mailTo">收件邮箱</label>
              <input id="mailTo" v-model="mailTo" type="email" placeholder="you@example.com" />
            </div>
            <button class="btn btn-ghost btn-sm" type="button" :disabled="testingMail" @click="testMail">{{ testingMail ? '发送中…' : '发送测试邮件' }}</button>
            <p class="adm-note">测试使用已保存的设置；如果刚修改参数，请先在弹窗里保存。</p>
          </section>
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">NOTE</span><h2>用途</h2></div></div>
            <p class="adm-note">SMTP 用于找回密码、注册验证码和后台测试邮件。开启注册邮箱验证前，必须保证这里已正确配置。</p>
          </section>
        </aside>
      </div>

      <div v-else-if="activeTab === 'update'" class="adm-update-wrap">
        <OtaPanel />
      </div>

      <div v-else-if="activeTab === 'ai'" class="adm-settings-grid">
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
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">CAPABILITY</span><h2>已接入能力</h2></div></div>
            <ul class="adm-hint-list">
              <li><b>AI 文章</b><span>生成草稿、审核文章</span></li>
              <li><b>AI 评论</b><span>审核评论、总结评论区</span></li>
              <li><b>AI 摘要</b><span>编辑器内生成文章摘要</span></li>
            </ul>
          </section>
        </aside>
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
                <span>注册必须验证邮箱</span>
                <input type="checkbox" :checked="boolValue(config.registrationVerifyEmail) || boolValue(config.registrationEnabled)" disabled />
                <i class="adm-switch is-disabled" aria-hidden="true"></i>
              </label>
              <p class="adm-note">开放注册后强制开启：提交注册前必须获取并填写邮箱验证码。请先配置可用的 SMTP。</p>
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
              <li>填写邮箱并点击“获取验证码”</li>
              <li>验证码 10 分钟内有效，同一邮箱 60 秒内不可重复发送</li>
              <li>填写验证码、用户名与密码完成注册</li>
              <li>注册成功后自动登录</li>
            </ol>
          </section>
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">DEPENDENCY</span><h2>依赖检查</h2></div></div>
            <dl class="adm-facts">
              <div><dt>SMTP</dt><dd>{{ boolValue(config.smtpEnabled) ? '已启用' : '未启用' }}</dd></div>
              <div><dt>注册</dt><dd>{{ boolValue(config.registrationEnabled) ? '开放' : '关闭' }}</dd></div>
            </dl>
            <p v-if="boolValue(config.registrationEnabled) && !boolValue(config.smtpEnabled)" class="adm-note danger-text">注册已开放但 SMTP 未启用，验证码将无法发送。请先配置 SMTP 或关闭注册。</p>
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
            <div class="adm-card-head"><div><span class="adm-card-kicker">VERSION</span><h2>版本与进程</h2></div></div>
            <dl class="adm-facts">
              <div><dt>版本</dt><dd>{{ version?.version || '—' }} <button class="btn-text" type="button" @click="copyVersion">复制</button></dd></div>
              <div><dt>构建</dt><dd>{{ ota?.jarPath || '—' }}</dd></div>
              <div><dt>Java</dt><dd>{{ version?.java || '—' }}</dd></div>
              <div><dt>系统</dt><dd>{{ version?.os || '—' }}</dd></div>
            </dl>
            <button class="btn btn-ghost btn-sm" type="button" @click="loadRuntime">重新读取</button>
          </section>
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">LAST</span><h2>上次更新</h2></div></div>
            <div v-if="ota?.lastUpdate?.status" class="adm-last-update">
              <span class="adm-status" :class="ota.lastUpdate.status === 'SUCCESS' ? 'is-published' : 'is-pending'">{{ ota.lastUpdate.status }}</span>
              <p>{{ ota.lastUpdate.message || '—' }}</p>
              <span class="meta">{{ ota.lastUpdate.updatedAt ? formatDateTime(ota.lastUpdate.updatedAt) : '' }}</span>
            </div>
            <p v-else class="adm-note">还没有执行过更新。</p>
          </section>
        </aside>
      </div>
    </template>

    <div v-if="smtpOpen" class="adm-modal-wrap" @click.self="smtpOpen = false">
      <div class="adm-modal is-wide">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">SMTP</span><h2>配置 SMTP</h2><p class="meta">敏感密钥保存后不会回显，留空代表沿用旧值。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="smtpOpen = false">×</button>
        </div>
        <div class="form-grid">
          <div class="field span-2">
            <label class="adm-switch-label">
              <span>启用 SMTP 邮件</span>
              <input type="checkbox" :checked="boolValue(config.smtpEnabled)" @change="setBool('smtpEnabled', $event)" />
              <i class="adm-switch" aria-hidden="true"></i>
            </label>
          </div>
          <div class="field">
            <label for="smtpHost">SMTP 主机</label>
            <input id="smtpHost" v-model="config.smtpHost" type="text" placeholder="smtp.example.com" />
          </div>
          <div class="field">
            <label for="smtpPort">端口</label>
            <input id="smtpPort" v-model="config.smtpPort" type="number" placeholder="465" />
          </div>
          <div class="field">
            <label for="smtpUsername">登录账号</label>
            <input id="smtpUsername" v-model="config.smtpUsername" type="text" placeholder="you@example.com" />
          </div>
          <div class="field">
            <label for="smtpPassword">授权码 / 密码</label>
            <input id="smtpPassword" v-model="config.smtpPassword" type="password" :placeholder="config.smtpPasswordSet === 'true' ? '已设置，留空保持不变' : '请输入 SMTP 密码或授权码'" autocomplete="new-password" />
          </div>
          <div class="field">
            <label for="smtpFrom">发件人地址</label>
            <input id="smtpFrom" v-model="config.smtpFrom" type="email" placeholder="留空则使用登录账号" />
          </div>
          <div class="field">
            <label>加密方式</label>
            <div class="adm-check-row">
              <label><input type="checkbox" :checked="boolValue(config.smtpSsl)" @change="setBool('smtpSsl', $event)" /> SSL</label>
              <label><input type="checkbox" :checked="boolValue(config.smtpStarttls)" @change="setBool('smtpStarttls', $event)" /> STARTTLS</label>
            </div>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="smtpOpen = false">取消</button>
          <button class="btn" type="button" :disabled="savingSmtp" @click="saveSmtp">{{ savingSmtp ? '保存中…' : '保存 SMTP 设置' }}</button>
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
  </div>
</template>
