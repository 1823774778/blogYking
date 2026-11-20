<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import http from '../../api'
import { toast } from '../../state'

const overview = ref(null)
const config = ref({})
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const files = reactive({ items: [], total: 0, page: 0, size: 20, categories: [] })
const filters = reactive({ keyword: '', category: '', uploader: '' })
let searchTimer = null
let filesRequest = 0

const providerOptions = [
  { value: 'local', label: '本地存储' },
  { value: 's3', label: 'S3 兼容存储' },
  { value: 'webdav', label: 'WebDAV' },
  { value: 'openlist', label: 'OpenList' }
]

const providerMeta = {
  local: { label: '本地存储', kicker: 'LOCAL' },
  s3: { label: 'S3 兼容存储', kicker: 'S3' },
  webdav: { label: 'WebDAV', kicker: 'WEBDAV' },
  openlist: { label: 'OpenList', kicker: 'OPENLIST' }
}

const formProvider = computed(() => String(config.value.storageProvider || 'local'))
const savedProvider = computed(() => String(overview.value?.provider || 'local'))
const isLocal = computed(() => savedProvider.value === 'local')
const savedMeta = computed(() => providerMeta[savedProvider.value] || providerMeta.local)
const formMeta = computed(() => providerMeta[formProvider.value] || providerMeta.local)
const providerChanged = computed(() => formProvider.value !== savedProvider.value)
const categoryOptions = computed(() => files.categories?.length
  ? files.categories
  : (overview.value?.categories || []))
const totalCategoryCount = computed(() => categoryOptions.value.length)

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

function chooseCategory(category) {
  filters.category = filters.category === category ? '' : category
  loadFiles(0)
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
    const res = await http.put('/admin/config', config.value)
    config.value = res.data
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

async function testStorage() {
  if (testing.value) return
  const saved = await saveStorage(true)
  if (!saved) return
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
  await Promise.all([loadOverview(), loadConfig()])
  if (isLocal.value) await loadFiles(0)
})

onBeforeUnmount(() => {
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
          <span class="adm-card-kicker">{{ formMeta.kicker }}</span>
          <h2>存储方式</h2>
        </div>
        <span class="adm-status" :class="overview?.configured ? 'is-published' : 'is-draft'">
          {{ isLocal ? '本地目录' : (overview?.configured ? '配置可用' : '待完善') }}
        </span>
      </div>

      <div class="storage-mode-row">
        <div class="field storage-provider-field">
          <label for="storageProvider">存储方式</label>
          <select id="storageProvider" v-model="config.storageProvider">
            <option v-for="option in providerOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
          <p class="meta">
            {{ formProvider === 'local' ? '文件保存在当前 jar 同级的 local-storage 目录，并按类型自动分文件夹。' :
              formProvider === 'openlist' ? 'OpenList 使用 WebDAV 协议，地址通常是 https://域名/dav。' :
              formProvider === 'webdav' ? '填写完整的 WebDAV 根地址，账号密码使用 Basic Auth。' :
              '兼容 AWS S3、Cloudflare R2、MinIO、阿里云 OSS 等 S3 接口。' }}
          </p>
        </div>
        <div class="storage-mode-facts">
          <span>当前使用</span>
          <strong>{{ savedMeta.label }}</strong>
          <small v-if="providerChanged">保存后切换为 {{ formMeta.label }}</small>
          <small v-else>{{ isLocal ? overview?.root || '—' : overview?.endpoint || '—' }}</small>
        </div>
      </div>

      <div v-if="formProvider !== 'local'" class="form-grid storage-cloud-fields">
        <template v-if="formProvider === 's3'">
          <div class="field span-2">
            <label for="storageEndpoint">Endpoint</label>
            <input id="storageEndpoint" v-model="config.storageEndpoint" type="url" placeholder="https://s3.example.com" />
          </div>
          <div class="field">
            <label for="storageBucket">Bucket</label>
            <input id="storageBucket" v-model="config.storageBucket" type="text" placeholder="my-blog" />
          </div>
          <div class="field">
            <label for="storageRegion">Region</label>
            <input id="storageRegion" v-model="config.storageRegion" type="text" placeholder="auto / us-east-1" />
          </div>
          <div class="field">
            <label for="storageAccessKey">Access Key</label>
            <input id="storageAccessKey" v-model="config.storageAccessKey" type="text" placeholder="AKIA…" autocomplete="off" />
          </div>
          <div class="field">
            <label for="storageSecretKey">Secret Key</label>
            <input id="storageSecretKey" v-model="config.storageSecretKey" type="password" :placeholder="config.storageSecretKeySet === 'true' ? '已设置，留空保持不变' : '请输入 Secret Key'" autocomplete="new-password" />
          </div>
          <div class="field span-2">
            <label class="adm-check-line"><input type="checkbox" :checked="boolValue(config.storagePathStyle)" @change="setBool('storagePathStyle', $event)" /> 使用 Path-Style 访问</label>
            <p class="meta">MinIO、R2 等通常需要开启；AWS S3 可按服务商要求关闭。</p>
          </div>
        </template>

        <template v-else>
          <div class="field span-2">
            <label for="storageEndpoint">{{ formProvider === 'openlist' ? 'OpenList 地址' : 'WebDAV 地址' }}</label>
            <input id="storageEndpoint" v-model="config.storageEndpoint" type="url" :placeholder="formProvider === 'openlist' ? 'https://openlist.example.com/dav' : 'https://dav.example.com/remote.php/dav/files/user'" />
            <p class="meta">
              {{ formProvider === 'openlist' ? '可填写 https://域名/dav；只填域名时会自动补 /dav。' : '填写 WebDAV 根地址，例如 Nextcloud、群晖或通用 WebDAV 服务地址。' }}
            </p>
          </div>
          <div class="field">
            <label for="storageUsername">用户名</label>
            <input id="storageUsername" v-model="config.storageUsername" type="text" placeholder="WebDAV 用户名" autocomplete="username" />
          </div>
          <div class="field">
            <label for="storagePassword">密码</label>
            <input id="storagePassword" v-model="config.storagePassword" type="password" :placeholder="config.storagePasswordSet === 'true' ? '已设置，留空保持不变' : 'WebDAV 密码'" autocomplete="new-password" />
          </div>
        </template>

        <div class="field">
          <label for="storagePathPrefix">路径前缀</label>
          <input id="storagePathPrefix" v-model="config.storagePathPrefix" type="text" placeholder="blog" />
          <p class="meta">云端对象路径：前缀 / 分类文件夹 / 文件名。</p>
        </div>
        <div class="field">
          <label for="storagePublicBaseUrl">公开访问域名</label>
          <input id="storagePublicBaseUrl" v-model="config.storagePublicBaseUrl" type="url" placeholder="https://cdn.example.com" />
          <p class="meta">留空时按服务商规则拼接；OpenList 默认使用 /d/ 直链。</p>
        </div>
      </div>

      <div class="adm-form-actions">
        <button class="btn" type="button" :disabled="saving || testing" @click="saveStorage(false)">{{ saving ? '保存中…' : '保存存储设置' }}</button>
        <button class="btn btn-ghost" type="button" :disabled="testing || saving || formProvider === 'local'" @click="testStorage">{{ testing ? '测试中…' : '测试连接' }}</button>
        <span v-if="providerChanged" class="meta">测试连接会先保存当前表单</span>
      </div>
    </section>

    <template v-if="isLocal">
      <section class="adm-mini-stats">
        <div><span>当前存储</span><strong>本地目录</strong></div>
        <div><span>分类文件夹</span><strong>{{ totalCategoryCount }}</strong></div>
        <div><span>文件总数</span><strong>{{ overview?.localFiles ?? '—' }}</strong></div>
        <div><span>占用空间</span><strong>{{ formatBytes(overview?.localBytes) }}</strong></div>
      </section>

      <section class="adm-card storage-category-card">
        <div class="adm-card-head">
          <div><span class="adm-card-kicker">FOLDERS</span><h2>文件分类文件夹</h2></div>
          <button v-if="filters.category" class="btn-text" type="button" @click="chooseCategory(filters.category)">查看全部</button>
        </div>
        <div class="storage-category-grid">
          <button
            v-for="category in categoryOptions"
            :key="category.key"
            class="storage-category-folder"
            :class="{ 'is-active': filters.category === category.key }"
            type="button"
            @click="chooseCategory(category.key)"
          >
            <span class="storage-folder-icon" aria-hidden="true">{{ category.label.slice(0, 1) }}</span>
            <span class="storage-folder-copy">
              <b>{{ category.label }}</b>
              <small>{{ category.count || 0 }} 个 · {{ formatBytes(category.bytes) }}</small>
            </span>
          </button>
        </div>
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
                <td class="adm-file-name storage-file-name">
                  <b :title="file.originalName">{{ file.originalName }}</b>
                  <small>{{ file.categoryLabel }} / {{ file.path }}</small>
                </td>
                <td><span class="storage-type-pill">{{ file.typeLabel }}</span></td>
                <td class="storage-uploader">
                  <b>{{ file.uploaderName }}</b>
                  <small v-if="file.uploaderUsername && file.uploaderUsername !== file.uploaderName">@{{ file.uploaderUsername }}</small>
                </td>
                <td>{{ formatBytes(file.size) }}</td>
                <td class="storage-time">{{ file.uploadedAt }}<small v-if="file.modifiedAt !== file.uploadedAt">修改 {{ file.modifiedAt }}</small></td>
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
        <p class="adm-note">文件保存在当前 jar 同级的 <code>{{ overview?.jarDirectory || '—' }}/local-storage</code>，访问前缀为 <code>/uploads/</code>。分类文件夹：<code>images/</code>、<code>audio/</code>、<code>video/</code>、<code>documents/</code>、<code>archives/</code>、<code>other/</code>。</p>
        <p class="meta">实际根目录：<code>{{ overview?.root || '—' }}</code></p>
      </section>
    </template>

    <section v-else class="adm-card storage-online-summary">
      <div class="adm-card-head">
        <div><span class="adm-card-kicker">{{ savedMeta.kicker }}</span><h2>{{ savedMeta.label }} 线上存储</h2></div>
        <span class="adm-status" :class="overview?.configured ? 'is-published' : 'is-draft'">{{ overview?.configured ? '已连接配置' : '待完善' }}</span>
      </div>
      <p class="adm-note">当前已切换为线上存储，本页只显示云端连接与存储配置，不显示本地文件列表、本地目录统计或分类文件夹。</p>
      <dl class="adm-facts storage-online-facts">
        <div><dt>存储方式</dt><dd>{{ savedMeta.label }}</dd></div>
        <div><dt>Endpoint / 地址</dt><dd class="adm-break">{{ overview?.endpoint || '—' }}</dd></div>
        <div v-if="savedProvider === 's3'"><dt>Bucket</dt><dd>{{ overview?.bucket || '—' }}</dd></div>
        <div><dt>路径前缀</dt><dd>{{ overview?.pathPrefix || '—' }}</dd></div>
        <div><dt>公开域名</dt><dd class="adm-break">{{ overview?.publicBaseUrl || '（按服务商规则拼接）' }}</dd></div>
      </dl>
      <p class="meta">上传的图片、头像和音频会按分类前缀写入 {{ savedMeta.label }}，返回地址会直接写入文章或站点配置。需要回到本地存储时，在上方存储方式中切换并保存。</p>
    </section>
  </div>
</template>
