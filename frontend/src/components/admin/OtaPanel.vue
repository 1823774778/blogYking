<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import http from '../../api'
import { toast } from '../../state'
import { formatDateTime } from '../../utils'
import MarkdownBody from '../MarkdownBody.vue'

const ota = ref(null)
const otaFile = ref(null)
const otaInput = ref(null)
const otaUploading = ref(false)
const otaApplying = ref(false)
const otaProgress = ref(0)
const downloading = ref(false)
const downloadTarget = ref('')
const localOpen = ref(false)
const releases = ref(null)
const releasesLoading = ref(false)
const expandedRelease = ref('')
let otaTimer = null
let releasesRequest = 0

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

function refreshAll() {
  loadOta()
  loadReleases(true)
}

function safeReleaseLink(url) {
  return typeof url === 'string' && /^https:\/\/github\.com\//.test(url) ? url : ''
}

function toggleRelease(tag) {
  expandedRelease.value = expandedRelease.value === tag ? '' : tag
}

function openLocal() {
  if (otaApplying.value || downloading.value) return
  if (ota.value && !ota.value.canAutoUpdate) {
    toast(ota.value.modeNote || '当前运行模式不支持自动更新', 'error')
    return
  }
  localOpen.value = true
}

function pickOtaFile() {
  if (otaUploading.value || otaApplying.value) return
  otaInput.value?.click()
}

function onOtaFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.jar')) {
    toast('请选择 .jar 文件', 'error')
    event.target.value = ''
    return
  }
  if (file.size > 100 * 1024 * 1024) {
    toast('jar 不能超过 100MB', 'error')
    event.target.value = ''
    return
  }
  otaFile.value = file
  otaProgress.value = 0
}

function closeLocal() {
  if (otaUploading.value) return
  localOpen.value = false
  clearOtaFile()
}

function clearOtaFile() {
  otaFile.value = null
  otaProgress.value = 0
  if (otaInput.value) otaInput.value.value = ''
}

async function uploadOta() {
  if (!otaFile.value || otaUploading.value || otaApplying.value) return
  const data = new FormData()
  data.append('file', otaFile.value)
  otaUploading.value = true
  otaProgress.value = 0
  try {
    const res = await http.post('/admin/ota/upload', data, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000,
      onUploadProgress: (e) => {
        if (e.total) otaProgress.value = Math.round((e.loaded / e.total) * 100)
      }
    })
    toast(`校验通过：${res.data.version}，可以应用更新`)
    await loadOta()
    closeLocal()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    otaUploading.value = false
  }
}

async function onlineUpdate() {
  if (downloading.value || otaApplying.value) return
  const latest = releases.value?.latest
  if (!latest) {
    toast('还没有读取到可用的 Release', 'error')
    return
  }
  if (!releases.value?.updateAvailable) {
    toast('当前已是最新版本，无需在线更新', 'error')
    return
  }
  if (!latest.jarAsset) {
    toast('这个 Release 没有提供 jar 资产', 'error')
    return
  }
  downloading.value = true
  downloadTarget.value = latest.version
  try {
    const res = await http.post('/admin/ota/download', { tag: latest.tag }, { timeout: 180000 })
    toast(`已从 GitHub 下载并校验通过：${res.data.version}，确认后可立即更新`)
    await loadOta()
    await loadReleases()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    downloading.value = false
    downloadTarget.value = ''
  }
}

async function applyOta() {
  if (!ota.value?.pending || otaApplying.value) return
  const target = ota.value.pending
  const ok = window.confirm(
    `将备份当前 jar，替换为「${target.fileName}」（版本 ${target.version}），然后重启服务。\n\n更新期间站点会短暂不可用；独立运行时若新版本未通过健康检查，会自动回滚。确定继续？`
  )
  if (!ok) return
  otaApplying.value = true
  otaProgress.value = 0
  const baselineSha = ota.value.sha256
  try {
    await http.post('/admin/ota/apply', null, { timeout: 15000 })
    toast('更新已提交，等待服务重启…')
  } catch (e) {
    if (e.status) {
      otaApplying.value = false
      toast(e.message, 'error')
      return
    }
    toast('服务正在重启，等待新版本上线…')
  }
  pollOta(target, baselineSha)
}

function pollOta(target, baselineSha) {
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
        toast(`更新成功，当前版本 ${res.data.version}`)
        loadOta()
        loadReleases()
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

async function cancelPending() {
  if (!ota.value?.pending || otaApplying.value) return
  if (!window.confirm('确定丢弃已下载或已上传、尚未应用的 jar 吗？')) return
  try {
    await http.delete('/admin/ota/pending')
    toast('已取消待更新 jar')
    loadOta()
  } catch (e) {
    toast(e.message, 'error')
  }
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

onMounted(() => {
  loadOta()
  loadReleases()
})
onBeforeUnmount(stopOtaPoll)
</script>

<template>
  <div class="ota-panel">
    <div class="ota-toolbar">
      <div class="ota-toolbar-status">
        <span class="badge" :class="ota?.canAutoUpdate ? 'on' : 'warn'">{{ otaModeLabel(ota?.mode) }}</span>
        <span class="meta">当前版本 <code>{{ ota?.version || '—' }}</code></span>
      </div>
      <div class="ota-toolbar-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="releasesLoading" @click="refreshAll">{{ releasesLoading ? '检查中…' : '检查更新' }}</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="downloading || otaApplying" @click="openLocal">本地上传 jar</button>
      </div>
    </div>

    <div v-if="ota && ota.modeNote && ota.mode !== 'standalone'" class="ota-notice" :class="{ danger: !ota.canAutoUpdate }">{{ ota.modeNote }}</div>

    <section class="panel ota-online-card">
      <div class="ota-online-head">
        <div>
          <div class="section-title">在线更新</div>
          <p class="meta">直接从 GitHub Release 下载 jar，服务端校验启动类、配置和 SHA-256，通过后提示确认更新。</p>
        </div>
        <span v-if="releases?.latest" class="badge" :class="releases.updateAvailable ? 'warn' : 'on'">
          {{ releases.updateAvailable ? `可更新到 v${releases.latest.version}` : '当前已是最新' }}
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
        <div v-if="releases.latest.body" class="ota-release-body"><MarkdownBody :content="releases.latest.body" /></div>
        <p v-else class="meta ota-release-empty-body">这个版本没有填写更新内容。</p>
        <div class="ota-action-row">
          <button class="btn" type="button" :disabled="downloading || otaApplying || !releases.updateAvailable" @click="onlineUpdate">
            {{ downloading ? `正在下载 v${downloadTarget}…` : releases.updateAvailable ? `在线更新到 v${releases.latest.version}` : '已是最新版本' }}
          </button>
          <button class="btn btn-ghost" type="button" :disabled="downloading || otaApplying" @click="openLocal">本地上传 jar</button>
          <a v-if="safeReleaseLink(releases.latest.htmlUrl)" class="btn btn-ghost" :href="safeReleaseLink(releases.latest.htmlUrl)" target="_blank" rel="noopener noreferrer">查看 Release</a>
        </div>
        <p class="meta ota-action-note">在线更新不会接受任意 URL，只使用该 Release 提供的 jar 资产；下载校验通过后才会出现“立即更新并重启”。</p>
      </div>
      <div v-else-if="!releasesLoading" class="ota-release-empty">
        <p>{{ releases?.error || '还没有读取到发布版本。' }}</p>
        <button class="btn btn-ghost btn-sm" type="button" @click="loadReleases(true)">重新读取</button>
      </div>
    </section>

    <section v-if="ota?.pending" class="panel ota-pending">
      <div class="ota-pending-head"><div><span class="badge on">已校验</span><strong>{{ ota.pending.fileName }}</strong></div><span class="meta">{{ formatBytes(ota.pending.size) }}</span></div>
      <div class="ota-pending-meta"><span>目标版本 <b>{{ ota.pending.version }}</b></span><span>SHA-256 <code>{{ shortSha(ota.pending.sha256) }}</code></span></div>
      <div class="ota-pending-actions">
        <button class="btn" type="button" :disabled="otaApplying" @click="applyOta">{{ otaApplying ? '正在重启…' : '立即更新并重启' }}</button>
        <button class="btn btn-ghost" type="button" :disabled="otaApplying" @click="cancelPending">取消待更新</button>
      </div>
      <p class="ota-warn-text">应用后会短暂断站。独立运行模式会自动等待新版本健康检查，失败则回滚；更新完成后本页会自动恢复。</p>
    </section>

    <div v-if="otaApplying" class="ota-restarting"><span class="ota-spinner"></span><div><strong>正在更新并重启服务</strong><span>页面会自动重连并核对新版本，通常需要 10–40 秒。</span></div></div>

    <section v-if="ota?.lastUpdate?.status" class="panel ota-last-panel">
      <div class="section-title">上次更新</div>
      <div class="ota-last" :class="String(ota.lastUpdate.status).toLowerCase()">
        <span class="badge" :class="ota.lastUpdate.status === 'SUCCESS' ? 'on' : 'warn'">{{ ota.lastUpdate.status === 'SUCCESS' ? '成功' : ota.lastUpdate.status === 'ROLLED_BACK' ? '已回滚' : ota.lastUpdate.status }}</span>
        <div>{{ ota.lastUpdate.message || '—' }}</div>
        <div class="meta">{{ ota.lastUpdate.updatedAt || '' }}</div>
      </div>
    </section>

    <section class="panel ota-release-history">
      <div class="ota-release-history-head">
        <span class="section-title">历史版本</span>
        <span class="meta" v-if="releases">{{ releases.history?.length || 0 }} 个更早的发布</span>
        <span class="meta" v-if="releases?.cached">缓存于 {{ formatDateTime(releases.fetchedAt) }}</span>
      </div>
      <div v-for="release in releases?.history || []" :key="release.tag" class="ota-release-row">
        <button class="ota-release-row-head" type="button" :aria-expanded="expandedRelease === release.tag" @click="toggleRelease(release.tag)">
          <span class="ota-release-row-title"><b>v{{ release.version }}</b><span>{{ release.name }}</span><span v-if="release.prerelease" class="badge warn">预发布</span></span>
          <span class="ota-release-row-date">{{ formatDateTime(release.publishedAt) }}</span>
          <span class="ota-release-chevron">{{ expandedRelease === release.tag ? '收起' : '更新内容' }}</span>
        </button>
        <div v-if="expandedRelease === release.tag" class="ota-release-expanded">
          <div v-if="release.body" class="ota-release-body"><MarkdownBody :content="release.body" /></div>
          <p v-else class="meta">这个版本没有填写更新内容。</p>
          <div class="ota-release-row-actions">
            <a v-if="safeReleaseLink(release.htmlUrl)" class="btn-text" :href="safeReleaseLink(release.htmlUrl)" target="_blank" rel="noopener noreferrer">在 GitHub 查看</a>
          </div>
        </div>
      </div>
      <p v-if="!releases?.history?.length" class="meta ota-release-history-empty">暂无更早的历史发布。</p>
    </section>

    <div v-if="localOpen" class="adm-modal-wrap" @click.self="closeLocal">
      <div class="adm-modal ota-local-modal">
        <div class="adm-modal-head">
          <div>
            <span class="adm-card-kicker">LOCAL JAR</span>
            <h2>本地上传 jar</h2>
            <p class="meta">上传只落盘并校验，不会立刻重启；校验通过后统一出现在待更新区。</p>
          </div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="closeLocal">×</button>
        </div>
        <input ref="otaInput" class="ota-file-input" type="file" accept=".jar,application/java-archive" @change="onOtaFile" />
        <div class="ota-drop" :class="{ disabled: !ota?.canAutoUpdate || otaApplying || otaUploading }" role="button" tabindex="0" @click="pickOtaFile" @keydown.enter.prevent="pickOtaFile">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 16V4m0 0-4 4m4-4 4 4M5 16v3a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3" /></svg>
          <div><strong>{{ otaFile ? otaFile.name : '选择本地 jar 文件' }}</strong><span>{{ otaFile ? formatBytes(otaFile.size) : '支持 100MB 以内、由本项目构建的可执行 jar' }}</span></div>
          <button class="btn btn-ghost btn-sm" type="button" @click.stop="pickOtaFile">浏览</button>
        </div>
        <div v-if="otaUploading" class="ota-progress"><div class="ota-progress-track"><span :style="{ width: otaProgress + '%' }"></span></div><div class="ota-progress-meta">正在上传 {{ otaProgress }}%</div></div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="otaUploading" @click="closeLocal">取消</button>
          <button class="btn" type="button" :disabled="!otaFile || otaUploading || otaApplying || !ota?.canAutoUpdate" @click="uploadOta">{{ otaUploading ? '上传中…' : '上传并校验' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
