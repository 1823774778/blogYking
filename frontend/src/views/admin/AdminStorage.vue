<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'local'))
const overview = ref(null)
const config = ref({})
const files = reactive({ items: [], total: 0, page: 0, size: 20 })
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)

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

function isImage(name) {
  return /\.(png|jpg|jpeg|gif|webp|svg)$/i.test(name)
}

async function loadOverview() {
  try {
    overview.value = (await http.get('/admin/storage')).data
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadFiles(page = files.page) {
  loading.value = true
  try {
    const res = await http.get('/admin/storage/files', { params: { page, size: files.size } })
    files.items = res.data.items
    files.total = res.data.total
    files.page = res.data.page
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function loadConfig() {
  try {
    config.value = (await http.get('/admin/config')).data
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function saveStorage() {
  saving.value = true
  try {
    const res = await http.put('/admin/config', config.value)
    config.value = res.data
    await loadOverview()
    toast('存储设置已保存')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function testStorage() {
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
  if (!window.confirm(`确定删除「${file.name}」吗？引用这个地址的文章会显示坏图或失效音频。`)) return
  try {
    await http.delete('/admin/storage/files', { params: { name: file.name } })
    toast('文件已删除')
    await Promise.all([loadFiles(files.page), loadOverview()])
  } catch (e) {
    toast(e.message, 'error')
  }
}

watch(activeTab, (tab) => {
  if (tab === 'local') loadFiles(0)
})
onMounted(() => {
  loadOverview()
  loadConfig()
  loadFiles(0)
})
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">
        {{ activeTab === 'local' ? '本地文件' : '线上对象存储' }} ·
        {{ overview?.provider === 's3' ? '线上存储' : '本地存储' }}
      </span>
      <div class="adm-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" @click="loadOverview(); activeTab === 'local' ? loadFiles(files.page) : loadConfig()">刷新</button>
      </div>
    </div>

    <section class="adm-mini-stats">
      <div><span>当前存储</span><strong>{{ overview?.provider === 's3' ? '线上存储' : '本地存储' }}</strong></div>
      <div><span>本地文件</span><strong>{{ overview?.localFiles ?? '—' }}</strong></div>
      <div><span>本地占用</span><strong>{{ formatBytes(overview?.localBytes) }}</strong></div>
      <div><span>配置状态</span><strong class="adm-mini-state" :class="overview?.configured ? 'is-on' : 'is-off'">{{ overview?.configured ? '可用' : '待完善' }}</strong></div>
    </section>

    <template v-if="activeTab === 'local'">
      <div v-if="loading" class="adm-loading">正在读取本地文件…</div>
      <section v-else class="adm-card adm-table-card">
        <div class="adm-table-wrap">
          <table class="table adm-table adm-storage-table">
            <thead>
              <tr>
                <th>预览</th>
                <th>文件名</th>
                <th>大小</th>
                <th>修改时间</th>
                <th>访问地址</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="file in files.items" :key="file.name">
                <td>
                  <img v-if="isImage(file.name)" class="adm-storage-thumb" :src="file.url" alt="" />
                  <span v-else class="adm-storage-file">{{ file.name.split('.').pop()?.toUpperCase() || 'FILE' }}</span>
                </td>
                <td class="adm-file-name">{{ file.name }}</td>
                <td>{{ formatBytes(file.size) }}</td>
                <td>{{ file.modifiedAt }}</td>
                <td><a class="btn-text" :href="file.url" target="_blank" rel="noopener noreferrer">打开</a></td>
                <td><button class="btn-text danger" type="button" @click="removeFile(file)">删除</button></td>
              </tr>
              <tr v-if="!files.items.length">
                <td colspan="6" class="adm-empty-cell">本地存储还没有文件，上传封面或音频后会出现在这里。</td>
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
        <div class="adm-card-head"><div><span class="adm-card-kicker">LOCAL</span><h2>本地存储位置</h2></div></div>
        <p class="adm-note">文件保存在服务端 <code>{{ overview?.root || '—' }}</code>，访问前缀为 <code>/uploads/</code>。删除仅影响本地磁盘，不会同步删除线上对象。</p>
      </section>
    </template>

    <div v-else class="adm-settings-grid">
      <section class="adm-card">
        <div class="adm-card-head"><div><span class="adm-card-kicker">S3</span><h2>线上存储配置</h2></div><span class="adm-status" :class="overview?.configured ? 'is-published' : 'is-draft'">{{ overview?.configured ? '配置可用' : '待完善' }}</span></div>
        <div class="form-grid">
          <div class="field span-2">
            <label for="storageProvider">存储方式</label>
            <select id="storageProvider" v-model="config.storageProvider">
              <option value="local">本地存储</option>
              <option value="s3">线上存储（S3 兼容）</option>
            </select>
            <p class="meta">支持 AWS S3、Cloudflare R2、MinIO、阿里云 OSS 的 S3 兼容接口等。</p>
          </div>
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
          <div class="field">
            <label for="storagePathPrefix">路径前缀</label>
            <input id="storagePathPrefix" v-model="config.storagePathPrefix" type="text" placeholder="blog" />
          </div>
          <div class="field">
            <label for="storagePublicBaseUrl">公开访问域名</label>
            <input id="storagePublicBaseUrl" v-model="config.storagePublicBaseUrl" type="url" placeholder="https://cdn.example.com" />
            <p class="meta">留空时按 Endpoint/Bucket/路径拼接。</p>
          </div>
          <div class="field span-2">
            <label class="adm-check-line"><input type="checkbox" :checked="boolValue(config.storagePathStyle)" @change="setBool('storagePathStyle', $event)" /> 使用 Path-Style 访问</label>
            <p class="meta">MinIO、R2 等通常需要开启；AWS S3 可按服务商要求关闭。</p>
          </div>
        </div>
        <div class="adm-form-actions">
          <button class="btn" type="button" :disabled="saving" @click="saveStorage">{{ saving ? '保存中…' : '保存存储设置' }}</button>
          <button class="btn btn-ghost" type="button" :disabled="testing || config.storageProvider !== 's3'" @click="testStorage">{{ testing ? '测试中…' : '测试连接' }}</button>
        </div>
      </section>
      <aside class="adm-side-stack">
        <section class="adm-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">CURRENT</span><h2>当前存储</h2></div></div>
          <dl class="adm-facts">
            <div><dt>方式</dt><dd>{{ overview?.provider === 's3' ? '线上存储' : '本地存储' }}</dd></div>
            <div><dt>Endpoint</dt><dd class="adm-break">{{ overview?.endpoint || '—' }}</dd></div>
            <div><dt>Bucket</dt><dd>{{ overview?.bucket || '—' }}</dd></div>
            <div><dt>公开域名</dt><dd class="adm-break">{{ overview?.publicBaseUrl || '—' }}</dd></div>
            <div><dt>本地文件</dt><dd>{{ overview?.localFiles ?? '—' }} 个 · {{ formatBytes(overview?.localBytes) }}</dd></div>
          </dl>
        </section>
        <section class="adm-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">NOTE</span><h2>切换说明</h2></div></div>
          <p class="adm-note">切换到线上存储后，之后上传的图片与音频会写入对象存储；旧文件仍保留在本地。测试连接只执行 HeadBucket，不会上传测试文件。</p>
        </section>
      </aside>
    </div>
  </div>
</template>
