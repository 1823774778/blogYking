<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'
import { formatDate } from '../../utils'

const route = useRoute()
const router = useRouter()

const statusOptions = [
  { key: 'all', label: '全部文章' },
  { key: 'published', label: '已发布' },
  { key: 'pending', label: '待审核' },
  { key: 'draft', label: '草稿' },
  { key: 'rejected', label: '已退回' }
]
const statusMap = { published: 'PUBLISHED', pending: 'PENDING', draft: 'DRAFT', rejected: 'REJECTED' }

const validStatus = (value) => statusOptions.some((item) => item.key === value) ? value : 'all'
const activeStatus = ref(validStatus(String(route.query.tab || 'all')))
const keyword = ref('')
const loading = ref(false)
const posts = reactive({ records: [], total: 0, page: 1, size: 12 })
const stats = ref(null)

function statusLabel(status) {
  return ({ PUBLISHED: '已发布', PENDING: '待审核', DRAFT: '草稿', REJECTED: '已退回' }[status] || status)
}

function params(page = posts.page) {
  const p = { page, size: posts.size, mine: true }
  if (keyword.value.trim()) p.keyword = keyword.value.trim()
  if (statusMap[activeStatus.value]) p.status = statusMap[activeStatus.value]
  return p
}

async function load(page = 1) {
  loading.value = true
  try {
    const res = await http.get('/admin/posts', { params: params(page) })
    posts.records = res.data.records
    posts.total = res.data.total
    posts.page = res.data.page
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = (await http.get('/admin/stats', { params: { mine: true } })).data
  } catch {
    /* 统计失败不阻塞正文列表 */
  }
}

function setStatus(key) {
  if (key === activeStatus.value) return
  const query = key === 'all' ? {} : { tab: key }
  router.replace({ path: route.path, query })
}

function search() {
  load(1)
}

async function removePost(post) {
  if (!window.confirm(`确定删除《${post.title}》吗？相关评论、点赞、收藏会一并删除，且不可恢复。`)) return
  try {
    await http.delete(`/admin/posts/${post.id}`)
    toast('文章已删除')
    await Promise.all([load(posts.page), loadStats()])
  } catch (e) {
    toast(e.message, 'error')
  }
}

watch(() => route.query.tab, (value) => {
  const next = validStatus(String(value || 'all'))
  if (next === activeStatus.value) return
  activeStatus.value = next
  load(1)
})

onMounted(() => {
  load(1)
  loadStats()
})
</script>

<template>
  <div class="adm-page my-posts-page">
    <header class="content-hero">
      <div>
        <span class="content-kicker">MY ARTICLES</span>
        <h1>我的文章</h1>
        <p>你的写作清单。这里只陈列你署名的文章，管理与审核操作仍然留在文章管理。</p>
      </div>
      <router-link class="btn btn-sm content-create" to="/admin/posts/new">写新文章</router-link>
    </header>

    <section class="content-stats" aria-label="文章统计">
      <article>
        <span>当前结果</span>
        <strong>{{ posts.total || 0 }}</strong>
        <small>当前筛选下的文章</small>
      </article>
      <article>
        <span>已发布</span>
        <strong>{{ stats?.posts ?? '—' }}</strong>
        <small>正在前台展示</small>
      </article>
      <article>
        <span>待审核</span>
        <strong>{{ stats?.pendingPosts ?? '—' }}</strong>
        <small>等待站点审核</small>
      </article>
      <article>
        <span>草稿 / 退回</span>
        <strong>{{ (stats?.drafts ?? 0) + (stats?.rejectedPosts ?? 0) }}</strong>
        <small>{{ stats?.drafts ?? 0 }} 篇草稿 · {{ stats?.rejectedPosts ?? 0 }} 篇退回</small>
      </article>
    </section>

    <section class="content-toolbar">
      <div class="content-filters" role="tablist" aria-label="文章状态筛选">
        <button
          v-for="item in statusOptions"
          :key="item.key"
          type="button"
          class="content-filter"
          :class="{ active: activeStatus === item.key }"
          @click="setStatus(item.key)"
        >{{ item.label }}</button>
      </div>
      <div class="content-search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-4-4" />
        </svg>
        <input v-model="keyword" type="search" placeholder="搜索我的文章标题或摘要" @keyup.enter="search" />
        <button v-if="keyword" type="button" class="btn-text" @click="keyword = ''; search()">清除</button>
        <button type="button" class="btn btn-sm" @click="search">搜索</button>
      </div>
    </section>

    <div v-if="loading" class="adm-loading">正在读取你的文章…</div>

    <section v-else class="content-list">
      <article v-for="post in posts.records" :key="post.id" class="content-post">
        <div class="content-post-body">
          <div class="content-post-top">
            <span class="adm-status" :class="`is-${String(post.status).toLowerCase()}`">{{ statusLabel(post.status) }}</span>
            <span v-if="post.top" class="content-top-mark">置顶</span>
            <span class="content-date">{{ formatDate(post.updatedAt) }}</span>
          </div>
          <router-link class="content-post-title" :to="`/admin/posts/${post.id}/edit`">{{ post.title }}</router-link>
          <p class="content-post-summary">{{ post.summary || '还没有摘要，打开编辑器补充一句吧。' }}</p>
          <p v-if="post.reviewNote" class="content-review-note">审核意见：{{ post.reviewNote }}</p>
          <div class="content-post-meta">
            <span>{{ post.views }} 阅读</span>
            <span>{{ post.likes }} 喜欢</span>
            <span>{{ post.comments }} 评论</span>
          </div>
        </div>
        <div class="content-post-actions">
          <router-link class="btn-text" :to="`/admin/posts/${post.id}/edit`">继续编辑</router-link>
          <a class="btn-text" :href="`/post/${post.id}`" target="_blank" rel="noopener">前台预览</a>
          <button class="btn-text danger" type="button" @click="removePost(post)">删除</button>
        </div>
      </article>

      <div v-if="!posts.records.length" class="content-empty">
        <strong>还没有符合条件的文章</strong>
        <span>换个筛选条件，或者开始写一篇新的。</span>
        <router-link class="btn btn-sm" to="/admin/posts/new">写新文章</router-link>
      </div>
    </section>

    <div v-if="!loading && posts.total > posts.size" class="pager content-pager">
      <button type="button" :disabled="posts.page <= 1" @click="load(posts.page - 1)">上一页</button>
      <span class="meta">第 {{ posts.page }} 页 / 共 {{ posts.total }} 篇</span>
      <button type="button" :disabled="posts.page * posts.size >= posts.total" @click="load(posts.page + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.my-posts-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.content-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 28px 30px;
  border: 1px solid var(--adm-line);
  border-radius: 16px;
  background:
    radial-gradient(circle at 92% 12%, rgba(22, 93, 255, 0.10), transparent 32%),
    var(--adm-card);
  box-shadow: var(--adm-shadow);
}

.content-kicker {
  display: block;
  margin-bottom: 8px;
  color: var(--adm-accent);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.content-hero h1 {
  margin: 0;
  color: var(--adm-chrome-fg);
  font-size: clamp(26px, 4vw, 38px);
  line-height: 1.15;
}

.content-hero p {
  max-width: 620px;
  margin: 10px 0 0;
  color: var(--adm-chrome-muted);
  font-size: 14px;
  line-height: 1.7;
}

.content-create {
  flex: none;
}

.content-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.content-stats article {
  display: flex;
  flex-direction: column;
  min-height: 104px;
  padding: 16px 18px;
  border: 1px solid var(--adm-line);
  border-radius: 14px;
  background: var(--adm-card);
  box-shadow: var(--adm-shadow-soft);
}

.content-stats span {
  color: var(--adm-chrome-muted);
  font-size: 12px;
}

.content-stats strong {
  margin: 6px 0 4px;
  color: var(--adm-chrome-fg);
  font-size: 28px;
  line-height: 1;
}

.content-stats small {
  color: var(--adm-chrome-subtle);
  font-size: 11px;
}

.content-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.content-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px;
  border: 1px solid var(--adm-line);
  border-radius: 12px;
  background: var(--adm-card);
}

.content-filter {
  min-height: 34px;
  padding: 0 13px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--adm-chrome-muted);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.content-filter:hover,
.content-filter.active {
  background: var(--adm-chrome-active-bg);
  color: var(--adm-chrome-active-fg);
}

.content-search {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: min(100%, 440px);
  padding: 6px 8px 6px 12px;
  border: 1px solid var(--adm-line);
  border-radius: 12px;
  background: var(--adm-card);
}

.content-search svg {
  width: 17px;
  height: 17px;
  flex: none;
  color: var(--adm-chrome-subtle);
}

.content-search input {
  min-width: 0;
  flex: 1;
  height: 30px;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--adm-chrome-fg);
  font: inherit;
  font-size: 13px;
}

.content-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.content-post {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 22px;
  padding: 22px 24px;
  overflow: hidden;
  border: 1px solid var(--adm-line);
  border-radius: 14px;
  background: var(--adm-card);
  box-shadow: var(--adm-shadow-soft);
}

.content-post::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--adm-accent);
  opacity: 0.72;
}

.content-post-body {
  min-width: 0;
  flex: 1;
}

.content-post-top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 7px;
}

.content-top-mark {
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--adm-amber-bg, #fff4e8);
  color: var(--adm-amber, #d25f00);
  font-size: 11px;
  font-weight: 700;
}

.content-date {
  margin-left: auto;
  color: var(--adm-chrome-subtle);
  font-size: 11px;
}

.content-post-title {
  color: var(--adm-chrome-fg);
  font-size: 18px;
  font-weight: 700;
  line-height: 1.45;
  text-decoration: none;
}

.content-post-title:hover {
  color: var(--adm-accent);
}

.content-post-summary {
  display: -webkit-box;
  margin: 7px 0 0;
  overflow: hidden;
  color: var(--adm-chrome-muted);
  font-size: 13px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.content-review-note {
  margin: 8px 0 0;
  padding: 7px 10px;
  border-radius: 7px;
  background: var(--adm-amber-bg, #fff4e8);
  color: var(--adm-amber, #d25f00);
  font-size: 12px;
  line-height: 1.55;
}

.content-post-meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 10px;
  color: var(--adm-chrome-subtle);
  font-size: 11px;
}

.content-post-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 9px;
  flex: none;
  padding-top: 1px;
}

.content-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  padding: 62px 20px;
  border: 1px dashed var(--adm-line-strong);
  border-radius: 14px;
  background: var(--adm-card);
  color: var(--adm-chrome-muted);
  font-size: 13px;
  text-align: center;
}

.content-empty strong {
  color: var(--adm-chrome-fg);
  font-size: 15px;
}

.content-pager {
  margin-top: 2px;
}

@media (max-width: 980px) {
  .content-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .content-post {
    flex-direction: column;
    gap: 14px;
  }

  .content-post-actions {
    flex-direction: row;
    align-items: center;
  }
}

@media (max-width: 640px) {
  .content-hero {
    align-items: flex-start;
    flex-direction: column;
    padding: 22px 20px;
  }

  .content-stats {
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }

  .content-stats article {
    min-height: 88px;
    padding: 12px 13px;
  }

  .content-stats strong {
    font-size: 23px;
  }

  .content-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .content-search {
    min-width: 0;
  }

  .content-post {
    padding: 18px 18px 16px 20px;
  }

  .content-post-title {
    font-size: 16px;
  }
}
</style>
