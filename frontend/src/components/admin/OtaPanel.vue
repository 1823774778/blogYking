<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import http from '../../api'
import { toast } from '../../state'
import { formatDateTime } from '../../utils'
import MarkdownBody from '../MarkdownBody.vue'

const JOB_KEY = 'blog_ota_download_job'

const ota = ref(null)
const otaApplying = ref(false)
const releases = ref(null)
const releasesLoading = ref(false)
const cacheClearing = ref(false)
const latestExpanded = ref(true)
const historyOpen = ref(false)
const downloadJob = ref(null)
const downloadStarting = ref(false)
const config = ref({})
const proxyOpen = ref(false)
const proxyInput = ref('')
const proxySaving = ref(false)
const rollbackTarget = ref(null)
const localOpen = ref(false)
const localFile = ref(null)
const localUploading = ref(false)
const localProgress = ref(0)
const localError = ref('')
const localInput = ref(null)

let otaTimer = null
let downloadTimer = null
let releasesRequest = 0

const jobRunning = computed(() => downloadJob.value?.status === 'RUNNING')
const downloadPercent = computed(() => {
  if (!jobRunning.value) return 0
  return Math.max(0, Math.min(100, Number(downloadJob.value?.percent) || 0))
})
const pendingIsRollback = computed(() => {
  if (!ota.value?.pending) return false
  if (downloadJob.value?.mode === 'ROLLBACK') return true
  return compareVersions(ota.value.pending.version, ota.value.version) < 0
})
const primaryLabel = computed(() => {
  if (otaApplying.value) return '正在安装…'
  if (downloadStarting.value) return '开始下载…'
  if (jobRunning.value) return ''
  if (ota.value?.pending) return `安装 v${ota.value.pending.version}`
  if (releasesLoading.value && !releases.value) return '检查更新中…'
  if (releases.value?.updateAvailable) return '下载安装'
  if (!releasesLoading.value && !releases.value?.latest) return '重新检查'
  return '检查更新'
})
const primaryDisabled = computed(() =>
  otaApplying.value || jobRunning.value || downloadStarting.value || (releasesLoading.value && !releases.value)
)
const proxyConfigured = computed(() => Boolean(String(config.value.otaDownloadProxyPrefix || '').trim()))
const versionSourceLabel = computed(() => ({
  'jar-manifest': 'JAR manifest',
  'build-info': 'JAR build-info',
  'pom.properties': 'JAR pom.properties',
  config: '启动参数（未找到 JAR 版本）',
  unknown: '未知来源'
})[String(ota.value?.versionSource || '')] || 'JAR')
const localFileName = computed(() => localFile.value?.name || '')
const localFileSize = computed(() => localFile.value ? formatBytes(localFile.value.size) : '')

async function loadOta() {
  try {
    ota.value = (await http.get('/admin/ota/status')).data
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadReleases(refresh = false) {
  const requestId = ++releasesRequest
  releasesLoading.value = true
  try {
    const res = await http.get('/admin/ota/releases', {
      params: refresh ? { refresh: 1 } : undefined,
      timeout: 20000
    })
    if (requestId !== releasesRequest) return
    releases.value = res.data
    if (res.data?.error) toast(res.data.error, 'error')
  } catch (e) {
    if (requestId === releasesRequest) toast(e.message, 'error')
  } finally {
    if (requestId === releasesRequest) releasesLoading.value = false
  }
}

async function loadConfig() {
  try {
    config.value = (await http.get('/admin/config')).data || {}
  } catch (e) {
    // 代理设置读不到时仍允许查看更新，不阻断页面
  }
}

function refreshAll() {
  loadOta()
  loadReleases(true)
}

function toggleLatestContent() {
  latestExpanded.value = !latestExpanded.value
}

async function primaryAction() {
  if (primaryDisabled.value) return
  if (ota.value?.pending) {
    await applyOta()
    return
  }
  if (releases.value?.updateAvailable) {
    if (!releases.value.latest) {
      toast('还没有读取到可用的 Release', 'error')
      return
    }
    await startDownload(releases.value.latest, 'UPDATE')
    return
  }
  refreshAll()
}

function rememberJob(job) {
  downloadJob.value = job
  if (job?.id) {
    localStorage.setItem(JOB_KEY, job.id)
  }
}

function forgetJob() {
  downloadJob.value = null
  localStorage.removeItem(JOB_KEY)
  stopDownloadPoll()
}

async function startDownload(release, mode = 'UPDATE') {
  if (downloadStarting.value || jobRunning.value || otaApplying.value) return
  if (!release?.jarAsset) {
    toast('这个 Release 没有提供 jar 资产', 'error')
    return
  }
  downloadStarting.value = true
  try {
    const res = await http.post('/admin/ota/download', {
      tag: release.tag,
      mode
    }, { timeout: 30000 })
    rememberJob(res.data)
    toast(mode === 'ROLLBACK'
      ? `开始下载 v${release.version}，完成后可回档安装`
      : `开始下载 v${release.version}`)
    pollDownload(res.data.id)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    downloadStarting.value = false
  }
}

async function pollDownload(jobId) {
  stopDownloadPoll()
  const tick = async () => {
    if (!downloadJob.value || downloadJob.value.id !== jobId) return
    const previousStatus = downloadJob.value?.status
    try {
      const res = await http.get('/admin/ota/download/status', {
        params: { jobId },
        timeout: 8000
      })
      if (!res.data) {
        forgetJob()
        return
      }
      downloadJob.value = res.data
      if (res.data.status === 'SUCCESS') {
        stopDownloadPoll()
        await Promise.all([loadOta(), loadReleases(false)])
        if (previousStatus === 'RUNNING') {
          toast(`v${res.data.version} 下载并校验通过，可安装`)
        }
        return
      }
      if (res.data.status === 'FAILED') {
        stopDownloadPoll()
        localStorage.removeItem(JOB_KEY)
        toast(res.data.message || '下载失败', 'error')
        return
      }
      downloadTimer = setTimeout(tick, 700)
    } catch (e) {
      if (e.status === 401) {
        stopDownloadPoll()
        return
      }
      downloadTimer = setTimeout(tick, 1500)
    }
  }
  downloadTimer = setTimeout(tick, 250)
}

async function resumeDownload() {
  const jobId = localStorage.getItem(JOB_KEY)
  try {
    const res = await http.get('/admin/ota/download/status', {
      params: jobId ? { jobId } : undefined,
      timeout: 8000
    })
    if (!res.data) {
      if (jobId) forgetJob()
      return
    }
    if (!jobId && res.data.status !== 'RUNNING') return
    if (!jobId) {
      rememberJob(res.data)
    } else {
      downloadJob.value = res.data
    }
    if (res.data.status === 'RUNNING') {
      pollDownload(res.data.id)
      return
    }
    if (res.data.status === 'FAILED') {
      localStorage.removeItem(JOB_KEY)
      downloadJob.value = null
      toast(res.data.message || '下载失败', 'error')
      return
    }
    if (res.data.status === 'SUCCESS' && !ota.value?.pending) {
      forgetJob()
    }
  } catch (e) {
    // 页面刚恢复时接口可能尚未就绪，保留任务记录，下次进入再恢复
  }
}

async function applyOta() {
  if (!ota.value?.pending || otaApplying.value) return
  const target = ota.value.pending
  const rollback = pendingIsRollback.value
  const message = rollback
    ? `即将回档到 v${target.version}，服务会用旧版本替换当前程序并重启。\n\n风险提示：旧版本可能不兼容新版本产生或修改的数据，回档后新版本功能不可用；请先备份 data、config 与 uploads。\n\n确定回档？`
    : `将备份当前 jar，替换为「${target.fileName}」（版本 ${target.version}），然后重启服务。\n\n更新期间站点会短暂不可用；独立运行时若新版本未通过健康检查，会自动回滚。确定继续？`
  if (!window.confirm(message)) return
  otaApplying.value = true
  const baselineSha = ota.value.sha256
  try {
    await http.post('/admin/ota/apply', null, { timeout: 15000 })
    toast(rollback ? '回档已提交，等待服务重启…' : '更新已提交，等待服务重启…')
  } catch (e) {
    if (e.status) {
      otaApplying.value = false
      toast(e.message, 'error')
      return
    }
    toast('服务正在重启，等待新版本上线…')
  }
  pollOta(target, baselineSha, rollback)
}

function pollOta(target, baselineSha, rollback) {
  stopOtaPoll()
  const startedAt = Date.now()
  const tick = async () => {
    try {
      const res = await http.get('/admin/ota/status', { timeout: 5000 })
      ota.value = res.data
      const last = res.data.lastUpdate || {}
      if (res.data.sha256 === target.sha256
          || (last.status === 'SUCCESS' && last.targetSha === target.sha256)) {
        otaApplying.value = false
        forgetJob()
        toast(rollback
          ? `已回档到 ${res.data.version}，历史版本缓存已自动清理`
          : `更新成功，当前版本 ${res.data.version}，历史版本缓存已自动清理`)
        loadOta()
        loadReleases(false)
        return
      }
      if (last.status === 'ROLLED_BACK' || last.status === 'FAILED') {
        otaApplying.value = false
        toast(last.message || '更新失败，已回滚', 'error')
        loadOta()
        return
      }
    } catch (e) {
      // 重启过程中接口短暂不可用，继续轮询
    }
    if (Date.now() - startedAt > 120000) {
      otaApplying.value = false
      toast('更新仍在进行或服务未恢复，请稍后刷新检查', 'error')
      return
    }
    otaTimer = setTimeout(tick, 2000)
  }
  otaTimer = setTimeout(tick, 2500)
}

function stopOtaPoll() {
  if (otaTimer) {
    clearTimeout(otaTimer)
    otaTimer = null
  }
}

function stopDownloadPoll() {
  if (downloadTimer) {
    clearTimeout(downloadTimer)
    downloadTimer = null
  }
}

async function cancelPending() {
  if (!ota.value?.pending || otaApplying.value) return
  if (!window.confirm('确定丢弃已下载、尚未安装的 jar 吗？')) return
  try {
    await http.delete('/admin/ota/pending')
    forgetJob()
    toast('已取消待安装 jar')
    loadOta()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function clearCache() {
  if (cacheClearing.value || jobRunning.value || otaApplying.value) return
  cacheClearing.value = true
  try {
    const res = await http.post('/admin/ota/cache/clear', null, { timeout: 30000 })
    const result = res.data || {}
    const removed = Number(result.removedFiles || 0) + Number(result.removedTasks || 0)
    forgetJob()
    await Promise.all([loadOta(), loadReleases(true)])
    if (result.removedPending) {
      toast('已清除已下载的待安装包和下载缓存')
    } else if (removed > 0) {
      toast(`已清除 ${removed} 项已下载内容与任务记录`)
    } else {
      toast('没有可清理的已下载内容')
    }
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    cacheClearing.value = false
  }
}

function openLocalUpgrade() {
  localFile.value = null
  localProgress.value = 0
  localError.value = ''
  localOpen.value = true
}

function chooseLocalFile(event) {
  const file = event.target.files?.[0] || null
  localFile.value = file
  localError.value = ''
  localProgress.value = 0
}

async function uploadLocalJar() {
  if (!localFile.value || localUploading.value) return
  if (!localFile.value.name.toLowerCase().endsWith('.jar')) {
    localError.value = '只支持上传 .jar 文件'
    return
  }
  if (!ota.value?.canAutoUpdate) {
    localError.value = ota.value?.modeNote || '当前运行方式不支持自动替换 jar'
    return
  }
  localUploading.value = true
  localProgress.value = 0
  localError.value = ''
  const form = new FormData()
  form.append('file', localFile.value)
  try {
    const res = await http.post('/admin/ota/upload', form, {
      timeout: 180000,
      onUploadProgress: (event) => {
        if (event.total) {
          localProgress.value = Math.min(99, Math.round((event.loaded / event.total) * 100))
        }
      }
    })
    localProgress.value = 100
    localOpen.value = false
    await loadOta()
    toast(`本地 jar 已校验通过，待安装 v${res.data?.version || '未知'}`)
  } catch (e) {
    localError.value = e.message || '本地升级包上传失败'
  } finally {
    localUploading.value = false
    if (localInput.value) localInput.value.value = ''
  }
}

function openProxy() {
  proxyInput.value = String(config.value.otaDownloadProxyPrefix || '')
  proxyOpen.value = true
}

function normalizeProxyInput(raw) {
  const trimmed = String(raw || '').trim()
  if (!trimmed) return ''
  let value = trimmed
  if (!/^http(s)?:\/\//i.test(value)) value = `https://${value}`
  if (!value.endsWith('/')) value += '/'
  const url = new URL(value)
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('只支持 http 或 https')
  if (!url.hostname) throw new Error('请输入有效域名')
  if (url.username || url.password) throw new Error('代理前缀不能包含账号密码')
  return url.toString()
}

async function saveProxy() {
  if (proxySaving.value) return
  let normalized
  try {
    normalized = normalizeProxyInput(proxyInput.value)
  } catch (e) {
    toast(e.message || '代理前缀无效', 'error')
    return
  }
  proxySaving.value = true
  try {
    config.value = (await http.put('/admin/config', {
      otaDownloadProxyPrefix: normalized
    })).data || config.value
    proxyOpen.value = false
    toast(normalized ? `代理前缀已保存：${normalized}` : '已切换为直连下载')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    proxySaving.value = false
  }
}

function openRollback(release) {
  if (jobRunning.value || otaApplying.value || downloadStarting.value) return
  if (!release?.jarAsset) {
    toast('这个 Release 没有提供 jar 资产', 'error')
    return
  }
  rollbackTarget.value = release
}

function confirmRollback() {
  const release = rollbackTarget.value
  rollbackTarget.value = null
  if (release) startDownload(release, 'ROLLBACK')
}

function otaModeLabel(mode) {
  return ({ standalone: '独立运行', systemd: 'systemd', docker: 'Docker', dev: '开发模式', disabled: '已关闭' })[mode] || mode || '—'
}

function shortSha(sha) {
  return sha ? `${sha.slice(0, 10)}…` : '—'
}

function formatBytes(bytes) {
  if (!bytes) return '—'
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function compareVersions(left, right) {
  const a = String(left || '').split(/[^0-9]+/)
  const b = String(right || '').split(/[^0-9]+/)
  const max = Math.max(a.length, b.length)
  for (let i = 0; i < max; i += 1) {
    const av = Number(a[i] || 0)
    const bv = Number(b[i] || 0)
    if (av !== bv) return av - bv
  }
  return 0
}

onMounted(async () => {
  await loadOta()
  loadConfig()
  await resumeDownload()
  loadReleases(true)
})
onBeforeUnmount(() => {
  stopOtaPoll()
  stopDownloadPoll()
})
</script>

<template>
  <div class="ota-panel">
    <div class="ota-toolbar">
      <div class="ota-toolbar-status">
        <span class="badge" :class="ota?.canAutoUpdate ? 'on' : 'warn'">{{ otaModeLabel(ota?.mode) }}</span>
        <span class="meta">当前版本 <code>{{ ota?.version || '—' }}</code><span class="ota-version-source">读取自 {{ versionSourceLabel }}</span></span>
      </div>
      <div class="ota-toolbar-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="localUploading || otaApplying || jobRunning" @click="openLocalUpgrade">本地升级</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="cacheClearing || jobRunning || otaApplying" @click="clearCache">
          {{ cacheClearing ? '清除中…' : '清除缓存' }}
        </button>
        <button class="btn btn-ghost btn-sm ota-proxy-btn" type="button" @click="openProxy">
          代理前缀
          <i v-if="proxyConfigured" class="ota-proxy-dot" aria-hidden="true"></i>
        </button>
        <button class="btn btn-ghost btn-sm" type="button" @click="historyOpen = true">
          历史版本
          <span v-if="releases?.history?.length" class="ota-history-count">{{ releases.history.length }}</span>
        </button>
        <button
          class="btn ota-primary-action"
          :class="{ 'is-downloading': jobRunning || downloadStarting, 'is-idle': !releases?.updateAvailable && !ota?.pending }"
          type="button"
          :disabled="primaryDisabled"
          :aria-label="jobRunning ? `正在下载 ${downloadPercent}%` : (downloadStarting ? '正在准备下载' : primaryLabel)"
          @click="primaryAction"
        >
          <span v-if="jobRunning || downloadStarting" class="ota-btn-progress" :style="{ width: `${downloadPercent}%` }" aria-hidden="true"></span>
          <span v-if="!jobRunning && !downloadStarting" class="ota-btn-label" :class="{ 'is-check': !ota?.pending && !releases?.updateAvailable }">
            <svg v-if="!ota?.pending && !releases?.updateAvailable" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <path d="M20 11a8 8 0 1 0-2.3 6.6M20 4v7h-7" />
            </svg>
            {{ primaryLabel }}
          </span>
        </button>
      </div>
    </div>

    <div v-if="ota && ota.modeNote && ota.mode !== 'standalone'" class="ota-notice" :class="{ danger: !ota.canAutoUpdate }">{{ ota.modeNote }}</div>

    <section class="panel ota-online-card">
      <div class="ota-online-head">
        <div>
          <div class="section-title">最新版本</div>
        </div>
        <span v-if="releases?.latest" class="badge" :class="releases.updateAvailable ? 'warn' : 'on'">
          {{ releases.updateAvailable ? `有更新 v${releases.latest.version}` : '暂无更新' }}
        </span>
      </div>

      <div v-if="releasesLoading && !releases" class="ota-release-loading"><span class="ota-spinner"></span><span>正在读取 GitHub Releases…</span></div>
      <div v-else-if="releases?.latest" class="ota-online-body">
        <div class="ota-online-version">
          <div>
            <h3>{{ releases.latest.name }}</h3>
            <p class="meta"><code>v{{ releases.latest.version }}</code><span v-if="releases.latest.publishedAt"> · {{ formatDateTime(releases.latest.publishedAt) }}</span></p>
          </div>
          <p v-if="releases.latest.jarAsset" class="meta ota-asset">资产 <code>{{ releases.latest.jarAsset.name }}</code> · {{ formatBytes(releases.latest.jarAsset.size) }}</p>
        </div>
        <div v-if="releases.latest.body" class="ota-release-collapse">
          <button class="ota-release-toggle" type="button" :aria-expanded="latestExpanded" @click="toggleLatestContent">
            <span>更新内容</span>
            <span>{{ latestExpanded ? '收起' : '展开' }}</span>
          </button>
          <div v-if="latestExpanded" class="ota-release-body"><MarkdownBody :content="releases.latest.body" /></div>
        </div>
        <p v-else class="meta ota-release-empty-body">这个版本没有填写更新内容。</p>
      </div>
      <div v-else-if="!releasesLoading" class="ota-release-empty">
        <p>{{ releases?.error || '还没有读取到发布版本。' }}</p>
        <p class="meta">可点击右上角“清除缓存”后重新读取。</p>
      </div>
    </section>

    <section v-if="ota?.pending" class="panel ota-pending">
      <div class="ota-pending-head"><div><span class="badge on">已校验</span><strong>{{ ota.pending.fileName }}</strong></div><span class="meta">{{ formatBytes(ota.pending.size) }}</span></div>
      <div class="ota-pending-meta"><span>目标版本 <b>{{ ota.pending.version }}</b></span><span>来源 {{ ota.pending.source || '未知' }}</span><span>SHA-256 <code>{{ shortSha(ota.pending.sha256) }}</code></span></div>
      <div v-if="pendingIsRollback" class="ota-rollback-warning">
        <strong>回档风险</strong>
        <span>即将安装的是旧版本，可能不兼容现有数据；回档后新版本功能将不可用，请先备份数据库、配置与上传文件。</span>
      </div>
      <div class="ota-pending-actions">
        <button class="btn-text danger-text" type="button" :disabled="otaApplying" @click="cancelPending">取消待安装</button>
      </div>
      <p class="ota-warn-text">安装后会短暂断站。独立运行模式会自动等待新版本健康检查，失败则回滚；更新完成后本页会自动恢复。</p>
    </section>

    <div v-if="otaApplying" class="ota-restarting"><span class="ota-spinner"></span><div><strong>正在安装并重启服务</strong><span>页面会自动重连并核对新版本，通常需要 10–40 秒。</span></div></div>

    <div v-if="historyOpen" class="adm-modal-wrap" @click.self="historyOpen = false">
      <div class="adm-modal ota-history-modal">
        <div class="adm-modal-head">
          <div>
            <span class="adm-card-kicker">RELEASE HISTORY</span>
            <h2>历史版本</h2>
            <p class="meta">
              {{ releases?.history?.length || 0 }} 个更早的发布
              <span v-if="releases?.cached"> · 缓存于 {{ formatDateTime(releases.fetchedAt) }}</span>
            </p>
          </div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="historyOpen = false">×</button>
        </div>
        <div v-if="releases?.history?.length" class="ota-history-list">
          <details v-for="release in releases.history" :key="release.tag" class="ota-history-item">
            <summary>
              <span class="ota-history-version">v{{ release.version }}</span>
              <span class="ota-history-name">{{ release.name }}</span>
              <span v-if="release.prerelease" class="badge warn">预发布</span>
              <time>{{ formatDateTime(release.publishedAt) }}</time>
            </summary>
            <div class="ota-history-body">
              <div v-if="release.body" class="ota-release-body"><MarkdownBody :content="release.body" /></div>
              <p v-else class="meta">这个版本没有填写更新内容。</p>
              <button class="btn btn-ghost btn-sm" type="button" :disabled="jobRunning || otaApplying || !release.jarAsset" @click="openRollback(release)">回档到此版本</button>
            </div>
          </details>
        </div>
        <p v-else class="meta ota-release-history-empty">暂无更早的历史发布。</p>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="historyOpen = false">关闭</button>
        </div>
      </div>
    </div>

    <div v-if="localOpen" class="adm-modal-wrap" @click.self="!localUploading && (localOpen = false)">
      <div class="adm-modal ota-local-modal">
        <div class="adm-modal-head">
          <div>
            <span class="adm-card-kicker">LOCAL UPGRADE</span>
            <h2>本地升级</h2>
            <p class="meta">上传本博客的可执行 jar，校验通过后进入待安装区；不会自动替换当前程序。</p>
          </div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" :disabled="localUploading" @click="localOpen = false">×</button>
        </div>

        <div class="ota-local-current">
          <span>当前版本 <code>{{ ota?.version || '—' }}</code></span>
          <span class="meta">版本读取自 {{ versionSourceLabel }}</span>
        </div>

        <div v-if="!ota?.canAutoUpdate" class="ota-notice danger">{{ ota?.modeNote || '当前运行方式不支持自动替换 jar' }}</div>

        <div class="ota-local-picker">
          <input ref="localInput" class="ota-hidden-input" type="file" accept=".jar,application/java-archive" @change="chooseLocalFile" />
          <button class="btn btn-ghost" type="button" :disabled="localUploading" @click="localInput?.click()">选择 jar 文件</button>
          <div v-if="localFileName" class="ota-local-file">
            <strong>{{ localFileName }}</strong>
            <span class="meta">{{ localFileSize }}</span>
          </div>
          <p v-else class="meta">只支持本博客的 Spring Boot 可执行 jar，最大 100 MB。</p>
        </div>

        <div v-if="localUploading" class="ota-local-progress" role="progressbar" :aria-valuenow="localProgress" aria-valuemin="0" aria-valuemax="100">
          <span :style="{ width: `${localProgress}%` }"></span>
          <b>{{ localProgress }}%</b>
        </div>

        <p v-if="localError" class="ota-local-error">{{ localError }}</p>

        <p class="ota-warn-text">上传并校验会替换当前“待安装”包。安装阶段仍会备份当前 jar、重启服务，独立运行模式健康检查失败会自动回滚。</p>

        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="localUploading" @click="localOpen = false">取消</button>
          <button class="btn" type="button" :disabled="localUploading || !localFile || !ota?.canAutoUpdate" @click="uploadLocalJar">
            {{ localUploading ? '上传并校验中…' : '上传并校验' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="proxyOpen" class="adm-modal-wrap" @click.self="proxyOpen = false">
      <div class="adm-modal ota-proxy-modal">
        <div class="adm-modal-head">
          <div>
            <span class="adm-card-kicker">DOWNLOAD PROXY</span>
            <h2>代理前缀</h2>
            <p class="meta">用于加速从 GitHub 下载 Release 资产。配置保存到数据库，只影响后台下载。</p>
          </div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="proxyOpen = false">×</button>
        </div>
        <div class="field">
          <label for="otaProxyPrefix">代理域名或前缀</label>
          <input id="otaProxyPrefix" v-model="proxyInput" type="text" maxlength="500" placeholder="可选，例如 https://your-proxy.example/" autocomplete="off" />
          <p class="meta">留空即直连；填写后会加速 GitHub Release 下载。输入域名会自动补全 https:// 与末尾斜杠。</p>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="proxyOpen = false">取消</button>
          <button class="btn" type="button" :disabled="proxySaving" @click="saveProxy">{{ proxySaving ? '保存中…' : '保存代理设置' }}</button>
        </div>
      </div>
    </div>

    <div v-if="rollbackTarget" class="adm-modal-wrap" @click.self="rollbackTarget = null">
      <div class="adm-modal ota-rollback-modal">
        <div class="adm-modal-head">
          <div>
            <span class="adm-card-kicker">ROLLBACK</span>
            <h2>回档到 v{{ rollbackTarget.version }}</h2>
            <p class="meta">将下载这个历史版本的 jar，并覆盖当前运行的 v{{ ota?.version || '—' }}。</p>
          </div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="rollbackTarget = null">×</button>
        </div>
        <div class="ota-risk-box">
          <strong>请确认风险</strong>
          <ul>
            <li>旧版本可能不兼容新版本已写入或修改的数据库结构，可能导致部分数据无法读取。</li>
            <li>回档后，新版本才有的功能与配置将不可用；再次升级前建议完整备份。</li>
            <li>安装过程会重启服务，站点短暂不可用。</li>
          </ul>
        </div>
        <p class="meta">下载完成后不会自动安装，右上角按钮会变为“安装 v{{ rollbackTarget.version }}”，你可以再次确认后执行回档。</p>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="rollbackTarget = null">取消</button>
          <button class="btn" type="button" @click="confirmRollback">下载回档包</button>
        </div>
      </div>
    </div>
  </div>
</template>
