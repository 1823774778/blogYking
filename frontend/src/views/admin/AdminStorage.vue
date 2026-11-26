<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import http from '../../api'
import { toast } from '../../state'
import { formatDateTime } from '../../utils'

const overview = ref(null)
const config = ref({})
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const storageModalOpen = ref(false)
const storageDialog = ref(null)
const storageTrigger = ref(null)
const storageDraft = reactive({
  storageProvider: 'local',
  storageEndpoint: '',
  storageUsername: '',
  storagePassword: '',
  storagePathPrefix: 'blog',
  storagePublicBaseUrl: ''
})
const storageKeys = ['storageProvider', 'storageEndpoint', 'storageUsername', 'storagePassword',
  'storagePathPrefix', 'storagePublicBaseUrl']
const files = reactive({ items: [], total: 0, page: 0, size: 20, categories: [] })
const filters = reactive({ keyword: '', category: '', uploader: '' })
let searchTimer = null
let filesRequest = 0

const providerOptions = [
  { value: 'local', label: '本地存储' },
  { value: 'webdav', label: 'WebDAV' },
  { value: 'openlist', label: 'OpenList' }
]

function formatFileTime(value) {
  return formatDateTime(value) || '—'
}

const providerMeta = {
  local: { label: '本地存储', kicker: 'LOCAL' },
  webdav: { label: 'WebDAV', kicker: 'WEBDAV' },
  openlist: { label: 'OpenList', kicker: 'OPENLIST' }
}

const formProvider = computed(() => String(storageDraft.storageProvider || 'local'))
const savedProvider = computed(() => String(overview.value?.provider || config.value.storageProvider || 'local'))
const isLocal = computed(() => savedProvider.value === 'local')
const savedMeta = computed(() => providerMeta[savedProvider.value] || providerMeta.local)
const formMeta = computed(() => providerMeta[formProvider.value] || providerMeta.local)
const categoryOptions = computed(() => files.categories?.length
  ? files.categories
  : (overview.value?.categories || []))
const savedHint = computed(() => {
  if (savedProvider.value === 'local') return '文件保存在当前 jar 同级的 local-storage 目录。'
  if (savedProvider.value === 'openlist') return 'OpenList 使用 WebDAV 协议，地址通常是 https://域名/dav。'
  return '填写完整的 WebDAV 根地址，账号密码使用 Basic Auth。'
})

function boolValue(value) {
  return String(value).toLowerCase() === 'true'
}

function setBool(key, event) {
  config.value[key] = event.target.checked ? 'true' : 'false'
}

function formatBytes(bytes) {
  if (bytes === 0) return '0 B'
  if (!bytes) return '—'
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function isImage(file) {
  return file?.category === 'images' || String(file?.contentType || '').startsWith('image/')
}

async function loadOverview() {
  try {
    overview.value = (await http.get('/admin/storage')).data
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadConfig() {
  try {
    config.value = (await http.get('/admin/config')).data || {}
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadFiles(page = files.page) {
  if (!isLocal.value) {
    files.items = []
    files.total = 0
    files.page = 0
    return
  }
  const requestId = ++filesRequest
  loading.value = true
  try {
    const res = await http.get('/admin/storage/files', {
      params: {
        page,
        size: files.size,
        keyword: filters.keyword || undefined,
        category: filters.category || undefined,
        uploader: filters.uploader || undefined
      }
    })
    if (requestId !== filesRequest) return
    files.items = res.data.items || []
    files.total = res.data.total || 0
    files.page = res.data.page || 0
    files.categories = res.data.categories || []
  } catch (e) {
    if (requestId === filesRequest) toast(e.message, 'error')
  } finally {
    if (requestId === filesRequest) loading.value = false
  }
}

function scheduleSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadFiles(0), 280)
}

function syncStorageDraft() {
  for (const key of storageKeys) storageDraft[key] = config.value[key] || ''
  storageDraft.storageProvider = String(config.value.storageProvider || savedProvider.value || 'local')
  storageDraft.storagePathPrefix = config.value.storagePathPrefix || 'blog'
}

function openStorageModal() {
  syncStorageDraft()
  storageModalOpen.value = true
  nextTick(() => storageDialog.value?.focus())
}

function closeStorageModal() {
  storageModalOpen.value = false
  nextTick(() => storageTrigger.value?.focus())
}

function onStorageKeydown(event) {
  if (event.key === 'Escape' && storageModalOpen.value) closeStorageModal()
}

function storagePayload() {
  const payload = {}
  for (const key of storageKeys) payload[key] = storageDraft[key] == null ? '' : storageDraft[key]
  return payload
}

async function refresh() {
  await loadOverview()
  await loadConfig()
  if (isLocal.value) await loadFiles(files.page)
}

async function saveStorage(silent = false) {
  if (saving.value) return false
  saving.value = true
  try {
    const res = await http.put('/admin/config', storagePayload())
    config.value = res.data
    syncStorageDraft()
    await loadOverview()
    if (isLocal.value) await loadFiles(0)
    if (!silent) toast('存储设置已保存')
    return true
  } catch (e) {
    toast(e.message, 'error')
    return false
  } finally {
    saving.value = false
  }
}

async function saveStorageAndClose() {
  if (await saveStorage(false)) closeStorageModal()
}

async function runStorageTest() {
  if (testing.value) return
  if (formProvider.value === 'local') {
    toast('当前是本地存储，无需测试线上连接', 'error')
    return
  }
  testing.value = true
  try {
    const res = await http.post('/admin/storage/test')
    toast(res.data.message || '连接成功')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    testing.value = false
  }
}

async function testSavedStorage() {
  if (savedProvider.value === 'local') {
    toast('当前是本地存储，无需测试线上连接', 'error')
    return
  }
  if (testing.value) return
  testing.value = true
  try {
    const res = await http.post('/admin/storage/test')
    toast(res.data.message || '连接成功')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    testing.value = false
  }
}

async function testDraftStorage() {
  const saved = await saveStorage(true)
  if (!saved) return
  await runStorageTest()
}

async function removeFile(file) {
  const label = file.originalName || file.name
  if (!window.confirm(`确定删除「${label}」吗？引用这个地址的文章会显示坏图或失效附件。`)) return
  try {
    await http.delete('/admin/storage/files', { params: { path: file.path } })
    toast('文件已删除')
    await Promise.all([loadFiles(files.page), loadOverview()])
  } catch (e) {
    toast(e.message, 'error')
  }
}

function copyUrl(file) {
  if (!file?.url) return
  const absolute = new URL(file.url, window.location.origin).toString()
  navigator.clipboard?.writeText(absolute)
    .then(() => toast('访问地址已复制'))
    .catch(() => toast('复制失败，可右键打开链接复制', 'error'))
}

onMounted(async () => {
  document.addEventListener('keydown', onStorageKeydown)
  await Promise.all([loadOverview(), loadConfig()])
  syncStorageDraft()
  if (isLocal.value) await loadFiles(0)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onStorageKeydown)
  if (searchTimer) clearTimeout(searchTimer)
  filesRequest++
})
</script>

<template>
  <div class="adm-page storage-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">
        {{ savedMeta.label }} ·
        {{ isLocal ? `${overview?.localFiles ?? 0} 个本地文件` : (overview?.configured ? '已配置' : '待完善') }}
      </span>
      <div class="adm-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading || saving" @click="refresh">刷新</button>
      </div>
    </div>

    <section class="adm-card storage-mode-card">
      <div class="adm-card-head">
        <div>
          <span class="adm-card-kicker">{{ savedMeta.kicker }}</span>
          <h2>存储方式</h2>
        </div>
        <span class="adm-status" :class="overview?.configured ? 'is-published' : 'is-draft'">
          {{ isLocal ? '本地目录' : (overview?.configured ? '配置可用' : '待完善') }}
        </span>
      </div>

      <div class="storage-mode-row">
        <div class="storage-provider-field">
          <span class="storage-current-kicker">CURRENT PROVIDER</span>
          <div class="storage-current-row">
            <strong>{{ savedMeta.label }}</strong>
            <button ref="storageTrigger" class="btn btn-sm" type="button" @click="openStorageModal">配置存储方式</button>
            <button class="btn btn-ghost btn-sm" type="button" :disabled="testing || isLocal" @click="testSavedStorage">{{ testing ? '测试中…' : '测试连接' }}</button>
          </div>
          <p class="meta storage-provider-hint">{{ savedHint }}</p>
        </div>
        <div class="storage-mode-facts">
          <span>当前使用</span>
          <strong>{{ savedMeta.label }}</strong>
          <small>{{ isLocal ? overview?.root || '—' : overview?.endpoint || '—' }}</small>
        </div>
      </div>
    </section>

    <template v-if="isLocal">
      <section class="adm-mini-stats">
        <div><span>当前存储</span><strong>本地目录</strong></div>
        <div><span>文件总数</span><strong>{{ overview?.localFiles ?? '—' }}</strong></div>
        <div><span>占用空间</span><strong>{{ formatBytes(overview?.localBytes) }}</strong></div>
      </section>

      <div class="adm-toolbar storage-search-toolbar">
        <div class="adm-search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="11" cy="11" r="6.2" /><path d="m16 16 4 4" /></svg>
          <input v-model="filters.keyword" type="search" placeholder="搜索文件名、上传人、文件类型，例如 cover / deek / 图片" aria-label="搜索本地文件" @input="scheduleSearch" />
        </div>
        <select v-model="filters.category" class="storage-filter-select" aria-label="按分类筛选" @change="loadFiles(0)">
          <option value="">全部分类</option>
          <option v-for="category in categoryOptions" :key="category.key" :value="category.key">{{ category.label }}</option>
        </select>
        <input v-model="filters.uploader" class="storage-filter-input" type="search" placeholder="上传人" aria-label="按上传人筛选" @input="scheduleSearch" />
      </div>
      <p class="meta storage-search-hint">可搜索存储文件名、原始文件名、上传人、文件类型（如 JPG、MP3）或分类（图片、音频、文档）。</p>

      <div v-if="loading" class="adm-loading">正在读取本地文件…</div>
      <section v-else class="adm-card adm-table-card">
        <div class="adm-table-wrap">
          <table class="table adm-table adm-storage-table storage-file-table">
            <thead>
              <tr>
                <th>预览</th>
                <th>文件</th>
                <th>类型</th>
                <th>上传人</th>
                <th>大小</th>
                <th>上传 / 修改时间</th>
                <th>访问地址</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="file in files.items" :key="file.path">
                <td>
                  <img v-if="isImage(file)" class="adm-storage-thumb" :src="file.url" :alt="file.originalName" loading="lazy" />
                  <span v-else class="adm-storage-file">{{ file.type || 'FILE' }}</span>
                </td>
                <td class="storage-file-name">
                  <div class="storage-cell-stack">
                    <b :title="file.originalName">{{ file.originalName }}</b>
                    <small>{{ file.categoryLabel }} / {{ file.path }}</small>
                  </div>
                </td>
                <td><span class="storage-type-pill">{{ file.typeLabel }}</span></td>
                <td class="storage-uploader">
                  <div class="storage-cell-stack">
                    <b>{{ file.uploaderName }}</b>
                    <small v-if="file.uploaderUsername && file.uploaderUsername !== file.uploaderName">@{{ file.uploaderUsername }}</small>
                  </div>
                </td>
                <td class="storage-size">{{ formatBytes(file.size) }}</td>
                <td class="storage-time">
                  <div class="storage-cell-stack">
                    <span>{{ formatFileTime(file.uploadedAt) }}</span>
                    <small v-if="file.modifiedAt !== file.uploadedAt">修改 {{ formatFileTime(file.modifiedAt) }}</small>
                  </div>
                </td>
                <td>
                  <a class="btn-text" :href="file.url" target="_blank" rel="noopener noreferrer">打开</a>
                  <button class="btn-text" type="button" @click="copyUrl(file)">复制</button>
                </td>
                <td><button class="btn-text danger" type="button" @click="removeFile(file)">删除</button></td>
              </tr>
              <tr v-if="!files.items.length">
                <td colspan="8" class="adm-empty-cell">
                  {{ filters.keyword || filters.category || filters.uploader ? '没有匹配的文件，试试减少筛选条件。' : '本地存储还没有文件，上传封面、头像或音频后会按类型出现在这里。' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="files.total > files.size" class="pager">
          <button type="button" :disabled="files.page <= 0" @click="loadFiles(files.page - 1)">上一页</button>
          <span class="meta">第 {{ files.page + 1 }} 页 / 共 {{ files.total }} 个文件</span>
          <button type="button" :disabled="(files.page + 1) * files.size >= files.total" @click="loadFiles(files.page + 1)">下一页</button>
        </div>
      </section>

      <section class="adm-card">
        <div class="adm-card-head"><div><span class="adm-card-kicker">LOCAL PATH</span><h2>本地存储位置</h2></div></div>
        <p class="adm-note">文件保存在当前 jar 同级的 <code>{{ overview?.jarDirectory || '—' }}/local-storage</code>，访问前缀为 <code>/uploads/</code>。存储文件按实际类型自动归档，无需在页面上单独管理文件夹。</p>
        <p class="meta">实际根目录：<code>{{ overview?.root || '—' }}</code></p>
      </section>
    </template>

    <section v-else class="adm-card storage-online-summary">
      <div class="adm-card-head">
        <div><span class="adm-card-kicker">{{ savedMeta.kicker }}</span><h2>{{ savedMeta.label }} 线上存储</h2></div>
        <span class="adm-status" :class="overview?.configured ? 'is-published' : 'is-draft'">{{ overview?.configured ? '已连接配置' : '待完善' }}</span>
      </div>
      <p class="adm-note">当前已切换为线上存储，本页只显示云端连接与存储配置，不显示本地文件列表或本地目录统计。</p>
      <dl class="adm-facts storage-online-facts">
        <div><dt>存储方式</dt><dd>{{ savedMeta.label }}</dd></div>
        <div><dt>Endpoint / 地址</dt><dd class="adm-break">{{ overview?.endpoint || '—' }}</dd></div>
        <div><dt>路径前缀</dt><dd>{{ overview?.pathPrefix || '—' }}</dd></div>
        <div><dt>公开域名</dt><dd class="adm-break">{{ overview?.publicBaseUrl || '（按服务商规则拼接）' }}</dd></div>
      </dl>
      <p class="meta">上传的图片、头像和音频会按分类前缀写入 {{ savedMeta.label }}，返回地址会直接写入文章或站点配置。需要回到本地存储时，点击「配置存储方式」切换并保存。</p>
    </section>

    <div v-if="storageModalOpen" class="adm-modal-wrap" @click.self="closeStorageModal">
      <div ref="storageDialog" class="adm-modal storage-config-modal" role="dialog" aria-modal="true" aria-labelledby="storageDialogTitle" tabindex="-1">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">{{ formMeta.kicker }}</span><h2 id="storageDialogTitle">配置存储方式</h2><p class="meta">保存后立即切换；密码留空会沿用已保存的值。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="closeStorageModal">×</button>
        </div>
        <div class="form-grid">
          <div class="field span-2">
            <label for="storageProvider">存储方式</label>
            <select id="storageProvider" v-model="storageDraft.storageProvider">
              <option v-for="option in providerOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
            <p class="meta">
              {{ formProvider === 'local' ? '文件保存在当前 jar 同级的 local-storage 目录。' :
                formProvider === 'openlist' ? 'OpenList 使用 WebDAV 协议，地址通常是 https://域名/dav。' :
                '填写完整的 WebDAV 根地址，账号密码使用 Basic Auth。' }}
            </p>
          </div>
          <template v-if="formProvider !== 'local'">
            <div class="field span-2">
              <label for="storageEndpoint">{{ formProvider === 'openlist' ? 'OpenList 地址' : 'WebDAV 地址' }}</label>
              <input id="storageEndpoint" v-model="storageDraft.storageEndpoint" type="url" :placeholder="formProvider === 'openlist' ? 'https://openlist.example.com/dav' : 'https://dav.example.com/remote.php/dav/files/user'" />
              <p class="meta">{{ formProvider === 'openlist' ? '可填写 https://域名/dav；只填域名时会自动补 /dav。' : '填写 WebDAV 根地址，例如 Nextcloud、群晖或通用 WebDAV 服务地址。' }}</p>
            </div>
            <div class="field">
              <label for="storageUsername">用户名</label>
              <input id="storageUsername" v-model="storageDraft.storageUsername" type="text" placeholder="WebDAV 用户名" autocomplete="username" />
            </div>
            <div class="field">
              <label for="storagePassword">密码</label>
              <input id="storagePassword" v-model="storageDraft.storagePassword" type="password" :placeholder="config.storagePasswordSet === 'true' ? '已设置，留空保持不变' : 'WebDAV 密码'" autocomplete="new-password" />
            </div>
            <div class="field">
              <label for="storagePathPrefix">路径前缀</label>
              <input id="storagePathPrefix" v-model="storageDraft.storagePathPrefix" type="text" placeholder="blog" />
              <p class="meta">云端对象路径：路径前缀 / 文件名。</p>
            </div>
            <div class="field">
              <label for="storagePublicBaseUrl">公开访问域名</label>
              <input id="storagePublicBaseUrl" v-model="storageDraft.storagePublicBaseUrl" type="url" placeholder="https://cdn.example.com" />
              <p class="meta">留空时按服务商规则拼接；OpenList 默认使用 /d/ 直链。</p>
            </div>
          </template>
        </div>
        <div class="adm-modal-actions storage-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="saving || testing" @click="testDraftStorage">{{ testing ? '测试中…' : '测试连接' }}</button>
          <button class="btn btn-ghost" type="button" :disabled="saving" @click="closeStorageModal">取消</button>
          <button class="btn" type="button" :disabled="saving || testing" @click="saveStorageAndClose">{{ saving ? '保存中…' : '保存存储设置' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.storage-current-kicker { display: block; margin-bottom: 8px; color: var(--muted); font-size: 9px; font-weight: 700; letter-spacing: .14em; }
.storage-current-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.storage-current-row strong { margin-right: 6px; color: var(--ink); font-size: 18px; }
.storage-provider-hint { margin: 10px 0 0; max-width: 560px; }
.storage-config-modal { width: min(720px, calc(100vw - 32px)); }
.storage-modal-actions { justify-content: flex-end; }
@media (max-width: 680px) {
  .storage-current-row { align-items: stretch; flex-direction: column; }
  .storage-current-row strong { margin-right: 0; }
  .storage-modal-actions { flex-wrap: wrap; }
}
</style>
