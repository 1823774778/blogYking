<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api'
import { toast } from '../state'
import { formatDate, formatDateTime } from '../utils'
import MarkdownBody from '../components/MarkdownBody.vue'

const router = useRouter()
const tab = ref('overview')

const stats = ref(null)
const posts = reactive({ records: [], total: 0, page: 1, size: 10 })
const comments = reactive({ records: [], total: 0, page: 1, size: 10 })
const users = reactive({ records: [], total: 0, page: 1, size: 10 })
const config = ref({})
const version = ref(null)
const mailTo = ref('')
const savingConfig = ref(false)
const uploading = ref(false)
const fileInput = ref(null)
const loading = ref(false)

const ota = ref(null)
const otaFile = ref(null)
const otaInput = ref(null)
const otaUploading = ref(false)
const otaApplying = ref(false)
const otaProgress = ref(0)
let otaTimer = null

const releases = ref(null)
const releasesLoading = ref(false)
const expandedRelease = ref('')
let releasesRequest = 0

async function loadStats() {
  try {
    stats.value = (await http.get('/admin/stats')).data
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadPosts(page = posts.page) {
  try {
    const res = await http.get('/admin/posts', { params: { page, size: posts.size } })
    posts.records = res.data.records
    posts.total = res.data.total
    posts.page = res.data.page
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadComments(page = comments.page) {
  try {
    const res = await http.get('/admin/comments', { params: { page, size: comments.size } })
    comments.records = res.data.records
    comments.total = res.data.total
    comments.page = res.data.page
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadUsers(page = users.page) {
  try {
    const res = await http.get('/admin/users', { params: { page, size: users.size } })
    users.records = res.data.records
    users.total = res.data.total
    users.page = res.data.page
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadConfig() {
  try {
    config.value = (await http.get('/admin/config')).data
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function checkVersion() {
  try {
    version.value = (await http.get('/system/version')).data
  } catch (e) {
    toast(e.message, 'error')
  }
}

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

function refreshReleases() {
  if (releasesLoading.value) return
  loadReleases(true)
}

function toggleRelease(tag) {
  expandedRelease.value = expandedRelease.value === tag ? '' : tag
}

function safeReleaseLink(url) {
  return typeof url === 'string' && /^https:\/\/github\.com\//.test(url) ? url : ''
}

function pickOtaFile() {
  if (otaUploading.value || otaApplying.value) return
  if (ota.value && !ota.value.canAutoUpdate) {
    toast(ota.value.modeNote || '当前运行模式不支持自动更新', 'error')
    return
  }
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
    clearOtaFile()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    otaUploading.value = false
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
      const reachedTarget = res.data.sha256 === target.sha256
      if (reachedTarget || (last.status === 'SUCCESS' && last.targetSha === target.sha256)) {
        otaApplying.value = false
        toast(`更新成功，当前版本 ${res.data.version}`)
        checkVersion()
        loadOta()
        loadReleases()
        return
      }
      if (last.status === 'ROLLED_BACK' || last.status === 'FAILED') {
        otaApplying.value = false
        toast(last.message || '更新失败，已回滚', 'error')
        checkVersion()
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
  if (!window.confirm('确定丢弃已上传、尚未应用的 jar 吗？')) return
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

async function switchTab(next) {
  tab.value = next
  if (next === 'overview') loadStats()
  if (next === 'posts') loadPosts()
  if (next === 'comments') loadComments()
  if (next === 'users') loadUsers()
  if (next === 'ota') {
    checkVersion()
    loadOta()
    loadReleases()
  }
  if (next === 'settings') {
    loadConfig()
    checkVersion()
  }
}

async function removePost(post) {
  if (!window.confirm(`确定删除《${post.title}》吗？相关评论、点赞、收藏会一并删除。`)) return
  try {
    await http.delete(`/admin/posts/${post.id}`)
    toast('文章已删除')
    loadPosts()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function removeComment(comment) {
  if (!window.confirm('确定删除这条评论吗？')) return
  try {
    await http.delete(`/comments/${comment.id}`)
    toast('评论已删除')
    loadComments()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function toggleUser(user) {
  try {
    await http.put(`/admin/users/${user.id}/status`, { enabled: !user.enabled })
    toast(user.enabled ? '已禁用该账号' : '已启用该账号')
    loadUsers()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function changeRole(user) {
  const next = user.role === 'ADMIN' ? 'USER' : 'ADMIN'
  if (!window.confirm(`确定将 ${user.nickname || user.username} 的角色改为 ${next} 吗？`)) return
  try {
    await http.put(`/admin/users/${user.id}/role`, { role: next })
    toast('角色已更新')
    loadUsers()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function saveConfig() {
  savingConfig.value = true
  try {
    const res = await http.put('/admin/config', config.value)
    config.value = res.data
    toast('站点配置已保存，立即生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    savingConfig.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function onFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  uploading.value = true
  try {
    const res = await http.post('/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    config.value.avatar = res.data.url
    toast('头像已上传，记得保存配置')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

async function testMail() {
  try {
    await http.post('/admin/mail/test', { to: mailTo.value.trim() })
    toast('测试邮件已发送')
  } catch (e) {
    toast(e.message, 'error')
  }
}

onMounted(async () => {
  loading.value = true
  await Promise.all([loadStats(), loadPosts(), loadConfig()])
  loading.value = false
})

onBeforeUnmount(stopOtaPoll)
</script>

<template>
  <div>
    <div class="admin-head">
      <div>
        <h1>后台</h1>
        <p>写文章、管评论、看数据。所有修改即时生效，无需重启服务。</p>
      </div>
      <div style="display: flex; gap: 10px">
        <button class="btn btn-ghost btn-sm" type="button" @click="switchTab('settings')">站点设置</button>
        <router-link class="btn btn-sm" to="/admin/editor">写新文章</router-link>
      </div>
    </div>

    <div class="tabs">
      <button type="button" :class="{ on: tab === 'overview' }" @click="switchTab('overview')">概览</button>
      <button type="button" :class="{ on: tab === 'posts' }" @click="switchTab('posts')">文章</button>
      <button type="button" :class="{ on: tab === 'comments' }" @click="switchTab('comments')">评论</button>
      <button type="button" :class="{ on: tab === 'users' }" @click="switchTab('users')">用户</button>
      <button type="button" :class="{ on: tab === 'ota' }" @click="switchTab('ota')">更新</button>
      <button type="button" :class="{ on: tab === 'settings' }" @click="switchTab('settings')">设置</button>
    </div>

    <section v-if="tab === 'overview'">
      <div class="stat-cards">
        <div class="stat-card">
          <div class="k">已发布文章</div>
          <div class="v">{{ stats?.posts ?? '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="k">草稿</div>
          <div class="v">{{ stats?.drafts ?? '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="k">阅读量</div>
          <div class="v">{{ stats?.totalViews ?? '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="k">评论</div>
          <div class="v">{{ stats?.comments ?? '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="k">读者</div>
          <div class="v">{{ stats?.users ?? '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="k">喜欢</div>
          <div class="v">{{ stats?.likes ?? '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="k">收藏</div>
          <div class="v">{{ stats?.favorites ?? '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="k">文章总数</div>
          <div class="v">{{ (stats?.posts ?? 0) + (stats?.drafts ?? 0) }}</div>
        </div>
      </div>

      <div class="panel">
        <div class="section-title">最近文章</div>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>标题</th>
                <th>状态</th>
                <th>阅读</th>
                <th>更新</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="post in posts.records.slice(0, 5)" :key="post.id">
                <td class="title-cell">
                  <router-link :to="`/post/${post.id}`">{{ post.title }}</router-link>
                </td>
                <td>
                  <span class="badge" :class="post.status === 'PUBLISHED' ? 'on' : 'off'">
                    {{ post.status === 'PUBLISHED' ? '已发布' : '草稿' }}
                  </span>
                </td>
                <td>{{ post.views }}</td>
                <td>{{ formatDate(post.updatedAt) }}</td>
              </tr>
              <tr v-if="!posts.records.length">
                <td colspan="4" class="empty" style="padding: 30px 0">还没有文章</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <section v-else-if="tab === 'posts'">
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>标题</th>
              <th>状态</th>
              <th>阅读</th>
              <th>喜欢</th>
              <th>评论</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="post in posts.records" :key="post.id">
              <td class="title-cell">
                <router-link :to="`/post/${post.id}`">{{ post.title }}</router-link>
              </td>
              <td>
                <span class="badge" :class="post.status === 'PUBLISHED' ? 'on' : 'off'">
                  {{ post.status === 'PUBLISHED' ? '已发布' : '草稿' }}
                </span>
                <span v-if="post.top" class="badge warn" style="margin-left: 6px">置顶</span>
              </td>
              <td>{{ post.views }}</td>
              <td>{{ post.likes }}</td>
              <td>{{ post.comments }}</td>
              <td>{{ formatDate(post.updatedAt) }}</td>
              <td>
                <div class="ops">
                  <router-link class="btn-text" :to="`/admin/editor/${post.id}`">编辑</router-link>
                  <button class="btn-text" type="button" @click="removePost(post)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!posts.records.length">
              <td colspan="7" class="empty" style="padding: 40px 0">还没有文章，去写一篇吧</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager" v-if="posts.total > posts.size">
        <button type="button" :disabled="posts.page <= 1" @click="loadPosts(posts.page - 1)">上一页</button>
        <span class="meta">第 {{ posts.page }} 页 / 共 {{ posts.total }} 篇</span>
        <button type="button" :disabled="posts.page * posts.size >= posts.total" @click="loadPosts(posts.page + 1)">下一页</button>
      </div>
    </section>

    <section v-else-if="tab === 'comments'">
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>内容</th>
              <th>作者</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in comments.records" :key="c.id">
              <td style="max-width: 420px">{{ c.content }}</td>
              <td>{{ c.nickname }}</td>
              <td>{{ formatDateTime(c.createdAt) }}</td>
              <td>
                <button class="btn-text" type="button" @click="removeComment(c)">删除</button>
              </td>
            </tr>
            <tr v-if="!comments.records.length">
              <td colspan="4" class="empty" style="padding: 40px 0">还没有评论</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager" v-if="comments.total > comments.size">
        <button type="button" :disabled="comments.page <= 1" @click="loadComments(comments.page - 1)">上一页</button>
        <span class="meta">第 {{ comments.page }} 页 / 共 {{ comments.total }} 条</span>
        <button type="button" :disabled="comments.page * comments.size >= comments.total" @click="loadComments(comments.page + 1)">下一页</button>
      </div>
    </section>

    <section v-else-if="tab === 'users'">
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>昵称</th>
              <th>账号</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>状态</th>
              <th>注册时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users.records" :key="u.id">
              <td>{{ u.nickname || u.username }}</td>
              <td>{{ u.username }}</td>
              <td>{{ u.email }}</td>
              <td>
                <span class="badge" :class="u.role === 'ADMIN' ? 'warn' : ''">
                  {{ u.role === 'ADMIN' ? '管理员' : '读者' }}
                </span>
              </td>
              <td>
                <span class="badge" :class="u.enabled ? 'on' : 'off'">
                  {{ u.enabled ? '正常' : '已禁用' }}
                </span>
              </td>
              <td>{{ formatDate(u.createdAt) }}</td>
              <td>
                <div class="ops">
                  <button class="btn-text" type="button" @click="changeRole(u)">
                    {{ u.role === 'ADMIN' ? '降为读者' : '设为管理员' }}
                  </button>
                  <button class="btn-text" type="button" @click="toggleUser(u)">
                    {{ u.enabled ? '禁用' : '启用' }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>


    <section v-else-if="tab === 'ota'" class="ota-page">
      <div class="ota-head">
        <div>
          <span class="hero-eyebrow">OTA · UPDATE</span>
          <h2>本地上传 jar 更新</h2>
          <p>上传构建好的 blog.jar，校验通过后由独立更新进程备份、替换并重启。接口、数据库与现有数据不受影响。</p>
        </div>
        <span class="badge" :class="ota?.canAutoUpdate ? 'on' : 'warn'">
          {{ otaModeLabel(ota?.mode) }}
        </span>
      </div>

      <div class="panel ota-release-panel">
        <div class="ota-release-head">
          <div>
            <div class="section-title">GitHub 发布</div>
            <p class="meta">
              读取
              <a :href="releases?.repoUrl || 'https://github.com/1823774778/blogYking'" target="_blank" rel="noopener noreferrer">
                {{ releases?.repo || '1823774778/blogYking' }}
              </a>
              的 Releases，展示最新版本说明与历史版本更新内容。
            </p>
          </div>
          <div class="ota-release-head-actions">
            <a
              class="btn btn-ghost btn-sm"
              :href="releases?.repoUrl ? releases.repoUrl + '/releases' : 'https://github.com/1823774778/blogYking/releases'"
              target="_blank"
              rel="noopener noreferrer"
            >打开 Releases</a>
            <button class="btn btn-ghost btn-sm" type="button" :disabled="releasesLoading" @click="refreshReleases">
              {{ releasesLoading ? '读取中…' : '刷新发布信息' }}
            </button>
          </div>
        </div>

        <div v-if="releases?.stale || releases?.error" class="ota-notice danger">
          {{ releases.error || '暂时无法读取 GitHub Releases' }}
          <template v-if="releases?.fetchedAt">（当前显示 {{ formatDateTime(releases.fetchedAt) }} 的缓存）</template>
        </div>

        <div v-if="releasesLoading && !releases" class="ota-release-loading">
          <span class="ota-spinner"></span>
          <span>正在读取 GitHub Releases…</span>
        </div>

        <template v-else-if="releases?.latest">
          <div class="ota-release-latest">
            <div class="ota-release-latest-head">
              <div class="ota-release-title">
                <div class="ota-release-badges">
                  <span class="badge on">最新发布</span>
                  <span class="badge" :class="releases.updateAvailable ? 'warn' : 'on'">
                    {{ releases.updateAvailable ? `可更新到 v${releases.latest.version}` : '当前已是最新' }}
                  </span>
                  <span v-if="releases.latest.prerelease" class="badge warn">预发布</span>
                </div>
                <h3>{{ releases.latest.name }}</h3>
                <p class="meta">
                  <code>v{{ releases.latest.version }}</code>
                  <span v-if="releases.latest.publishedAt"> · 发布于 {{ formatDateTime(releases.latest.publishedAt) }}</span>
                </p>
              </div>
              <div class="ota-release-latest-actions">
                <a
                  v-if="safeReleaseLink(releases.latest.htmlUrl)"
                  class="btn btn-ghost btn-sm"
                  :href="safeReleaseLink(releases.latest.htmlUrl)"
                  target="_blank"
                  rel="noopener noreferrer"
                >查看 Release</a>
                <a
                  v-if="safeReleaseLink(releases.latest.jarAsset?.downloadUrl)"
                  class="btn btn-sm"
                  :href="safeReleaseLink(releases.latest.jarAsset?.downloadUrl)"
                  target="_blank"
                  rel="noopener noreferrer"
                >下载 jar</a>
              </div>
            </div>
            <div v-if="releases.latest.body" class="ota-release-body">
              <MarkdownBody :content="releases.latest.body" />
            </div>
            <p v-else class="meta ota-release-empty-body">这个版本没有填写更新内容。</p>
            <p v-if="releases.latest.jarAsset" class="meta ota-release-asset">
              资产：<code>{{ releases.latest.jarAsset.name }}</code>
              · {{ formatBytes(releases.latest.jarAsset.size) }}
              · 下载后在下方「上传并校验」即可应用
            </p>
          </div>

          <div class="ota-release-history">
            <div class="ota-release-history-head">
              <span class="section-title">历史版本</span>
              <span class="meta">{{ releases.history.length }} 个更早的发布</span>
              <span class="meta" v-if="releases.cached">缓存于 {{ formatDateTime(releases.fetchedAt) }}</span>
            </div>

            <div v-for="release in releases.history" :key="release.tag" class="ota-release-row">
              <button
                class="ota-release-row-head"
                type="button"
                :aria-expanded="expandedRelease === release.tag"
                @click="toggleRelease(release.tag)"
              >
                <span class="ota-release-row-title">
                  <b>v{{ release.version }}</b>
                  <span>{{ release.name }}</span>
                  <span v-if="release.prerelease" class="badge warn">预发布</span>
                </span>
                <span class="ota-release-row-date">{{ formatDateTime(release.publishedAt) }}</span>
                <span class="ota-release-chevron">{{ expandedRelease === release.tag ? '收起' : '更新内容' }}</span>
              </button>
              <div v-if="expandedRelease === release.tag" class="ota-release-expanded">
                <div v-if="release.body" class="ota-release-body">
                  <MarkdownBody :content="release.body" />
                </div>
                <p v-else class="meta">这个版本没有填写更新内容。</p>
                <div class="ota-release-row-actions">
                  <a
                    v-if="safeReleaseLink(release.htmlUrl)"
                    class="btn-text"
                    :href="safeReleaseLink(release.htmlUrl)"
                    target="_blank"
                    rel="noopener noreferrer"
                  >在 GitHub 查看</a>
                  <a
                    v-if="safeReleaseLink(release.jarAsset?.downloadUrl)"
                    class="btn-text"
                    :href="safeReleaseLink(release.jarAsset?.downloadUrl)"
                    target="_blank"
                    rel="noopener noreferrer"
                  >下载 jar</a>
                </div>
              </div>
            </div>

            <p v-if="!releases.history.length" class="meta ota-release-history-empty">
              这是仓库里第一个有更新内容的版本，暂无更早的历史发布。
            </p>
          </div>
        </template>

        <div v-else-if="!releasesLoading" class="ota-release-empty">
          <p>还没有读取到发布版本。</p>
          <p class="meta">仓库暂时没有 Release，或 GitHub API 不可用；发布第一个版本后这里会自动显示。</p>
          <button class="btn btn-ghost btn-sm" type="button" @click="refreshReleases">重新读取</button>
        </div>
      </div>

      <div class="ota-grid">
        <div class="ota-main">
          <div class="panel ota-upload-panel">
            <div class="section-title">上传新 jar</div>

            <div v-if="ota && ota.modeNote && ota.mode !== 'standalone'" class="ota-notice" :class="{ danger: !ota.canAutoUpdate }">
              {{ ota.modeNote }}
            </div>

            <input ref="otaInput" class="ota-file-input" type="file" accept=".jar,application/java-archive" @change="onOtaFile" />

            <div
              class="ota-drop"
              :class="{ disabled: !ota?.canAutoUpdate || otaApplying || otaUploading }"
              role="button"
              tabindex="0"
              @click="pickOtaFile"
              @keydown.enter.prevent="pickOtaFile"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <path d="M12 16V4m0 0-4 4m4-4 4 4" />
                <path d="M5 16v3a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3" />
              </svg>
              <div>
                <strong>{{ otaFile ? otaFile.name : '选择本地 jar 文件' }}</strong>
                <span>{{ otaFile ? formatBytes(otaFile.size) : '支持 100MB 以内、由本项目构建的可执行 jar' }}</span>
              </div>
              <button class="btn btn-ghost btn-sm" type="button" @click.stop="pickOtaFile">浏览</button>
            </div>

            <div v-if="otaFile" class="ota-file-row">
              <span>{{ otaFile.name }}</span>
              <button class="btn-text" type="button" @click="clearOtaFile">移除</button>
            </div>

            <div v-if="otaUploading" class="ota-progress">
              <div class="ota-progress-track"><span :style="{ width: otaProgress + '%' }"></span></div>
              <div class="ota-progress-meta">正在上传 {{ otaProgress }}%</div>
            </div>

            <div class="ota-upload-actions">
              <button
                class="btn"
                type="button"
                :disabled="!otaFile || otaUploading || otaApplying || !ota?.canAutoUpdate"
                @click="uploadOta"
              >
                {{ otaUploading ? '上传中…' : '上传并校验' }}
              </button>
              <span class="meta">上传只落盘，不会立刻重启；校验通过后再点“立即更新”。</span>
            </div>

            <div v-if="ota?.pending" class="ota-pending">
              <div class="ota-pending-head">
                <div>
                  <span class="badge on">已校验</span>
                  <strong>{{ ota.pending.fileName }}</strong>
                </div>
                <span class="meta">{{ formatBytes(ota.pending.size) }}</span>
              </div>
              <div class="ota-pending-meta">
                <span>目标版本 <b>{{ ota.pending.version }}</b></span>
                <span>SHA-256 <code>{{ shortSha(ota.pending.sha256) }}</code></span>
              </div>
              <div class="ota-pending-actions">
                <button class="btn" type="button" :disabled="otaApplying" @click="applyOta">
                  {{ otaApplying ? '正在重启…' : '立即更新并重启' }}
                </button>
                <button class="btn btn-ghost" type="button" :disabled="otaApplying" @click="cancelPending">取消待更新</button>
              </div>
              <p class="ota-warn-text">应用后会短暂断站。独立运行模式会自动等待新版本健康检查，失败则回滚；更新完成后本页会自动恢复。</p>
            </div>

            <div v-if="otaApplying" class="ota-restarting">
              <span class="ota-spinner"></span>
              <div>
                <strong>正在更新并重启服务</strong>
                <span>页面会自动重连并核对新版本，通常需要 10–40 秒。</span>
              </div>
            </div>
          </div>
        </div>

        <aside class="ota-side">
          <div class="panel">
            <div class="section-title">当前运行</div>
            <div class="ota-facts">
              <div><span>版本</span><strong>{{ version?.version || '—' }}</strong></div>
              <div><span>构建</span><strong>{{ version?.buildTime || '—' }}</strong></div>
              <div><span>已运行</span><strong>{{ version?.uptimeSeconds ?? '—' }} 秒</strong></div>
              <div><span>实例</span><strong class="mono">{{ shortSha(version?.instanceId) }}</strong></div>
              <div><span>模式</span><strong>{{ otaModeLabel(ota?.mode) }}</strong></div>
              <div><span>jar 大小</span><strong>{{ formatBytes(ota?.size) }}</strong></div>
            </div>
            <div class="ota-sha">
              <span>当前 SHA-256</span>
              <code>{{ shortSha(ota?.sha256) }}</code>
            </div>
            <button class="btn btn-ghost btn-sm" type="button" @click="checkVersion(); loadOta()">重新检查</button>
          </div>

          <div v-if="ota?.lastUpdate?.status" class="panel">
            <div class="section-title">上次更新</div>
            <div class="ota-last" :class="String(ota.lastUpdate.status).toLowerCase()">
              <span class="badge" :class="ota.lastUpdate.status === 'SUCCESS' ? 'on' : 'warn'">
                {{ ota.lastUpdate.status === 'SUCCESS' ? '成功' : ota.lastUpdate.status === 'ROLLED_BACK' ? '已回滚' : ota.lastUpdate.status }}
              </span>
              <div>{{ ota.lastUpdate.message || '—' }}</div>
              <div class="meta">{{ ota.lastUpdate.updatedAt || '' }}</div>
            </div>
          </div>

          <div class="panel ota-help">
            <div class="section-title">更新流程</div>
            <ol>
              <li>选择本机构建出的 <code>blog.jar</code></li>
              <li>上传后校验 Spring Boot 启动类、应用配置与 SHA-256</li>
              <li>应用时备份旧包到 <code>updates/backups</code></li>
              <li>独立更新进程替换并启动新版本</li>
              <li>健康检查失败时恢复旧包并重启</li>
            </ol>
          </div>
        </aside>
      </div>
    </section>

    <section v-else-if="tab === 'settings'" class="admin-settings">
      <div class="panel">
        <div class="section-title">站点信息</div>
        <div class="form-grid">
          <div class="field">
            <label for="siteName">站点名称</label>
            <input id="siteName" v-model="config.siteName" type="text" maxlength="32" />
          </div>
          <div class="field">
            <label for="siteKeywords">关键词</label>
            <input id="siteKeywords" v-model="config.siteKeywords" type="text" />
          </div>
          <div class="field span-2">
            <label for="siteDesc">站点描述</label>
            <input id="siteDesc" v-model="config.siteDesc" type="text" />
          </div>
          <div class="field span-2">
            <label>站点头像</label>
            <div style="display: flex; align-items: center; gap: 14px">
              <span class="avatar lg">
                <img v-if="config.avatar" :src="config.avatar" alt="" />
                <template v-else>墨</template>
              </span>
              <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickFile">
                {{ uploading ? '上传中…' : '上传图片' }}
              </button>
              <input ref="fileInput" type="file" accept="image/*" style="display: none" @change="onFile" />
            </div>
          </div>
          <div class="field span-2">
            <label for="announcement">公告</label>
            <textarea id="announcement" v-model="config.announcement" style="min-height: 70px"></textarea>
          </div>
          <div class="field span-2">
            <label for="about">关于页内容（支持 Markdown）</label>
            <textarea id="about" v-model="config.about" style="min-height: 140px"></textarea>
          </div>
          <div class="field">
            <label for="footer">页脚文案</label>
            <input id="footer" v-model="config.footer" type="text" />
          </div>
          <div class="field">
            <label for="icp">备案号</label>
            <input id="icp" v-model="config.icp" type="text" />
          </div>
          <div class="field">
            <label for="github">GitHub 链接</label>
            <input id="github" v-model="config.github" type="text" />
          </div>
          <div class="field">
            <label for="email">联系邮箱</label>
            <input id="email" v-model="config.email" type="text" />
          </div>
        </div>
        <button class="btn" type="button" :disabled="savingConfig" @click="saveConfig">
          {{ savingConfig ? '保存中…' : '保存站点配置' }}
        </button>
      </div>

      <div class="settings-side">
        <div class="panel">
          <div class="section-title">版本 / OTA</div>
          <div class="version-box">
            <div>版本：<span class="v">{{ version?.version || '—' }}</span></div>
            <div>构建：<span class="v">{{ version?.buildTime || '—' }}</span></div>
            <div>已运行：<span class="v">{{ version?.uptimeSeconds ?? '—' }} 秒</span></div>
            <div>Java：<span class="v">{{ version?.java || '—' }}</span></div>
          </div>
          <button class="btn btn-ghost btn-sm" type="button" style="margin-top: 14px" @click="checkVersion">检查更新</button>
          <p class="meta" style="margin: 12px 0 0; line-height: 1.7">
            替换 jar 并重启后，这里的版本与运行时间会变化，可用于确认新版本已上线。
          </p>
        </div>

        <div class="panel">
          <div class="section-title">SMTP 测试</div>
          <div class="field">
            <label for="mailto">收件邮箱</label>
            <input id="mailto" v-model="mailTo" type="email" placeholder="you@example.com" />
          </div>
          <button class="btn btn-ghost btn-sm" type="button" @click="testMail">发送测试邮件</button>
          <p class="meta" style="margin: 12px 0 0; line-height: 1.7">
            需在启动时配置 MAIL_ENABLED=true 及 spring.mail.* 环境变量。
          </p>
        </div>
      </div>
    </section>
  </div>
</template>
