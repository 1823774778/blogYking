<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'
import { formatDate, formatDateTime } from '../../utils'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'all'))
const keyword = ref('')
const posts = reactive({ records: [], total: 0, page: 1, size: 15 })
const stats = ref(null)
const loading = ref(false)
const reviewTarget = ref(null)
const reviewNote = ref('')
const reviewing = ref(false)
const aiReviewOpen = ref(false)
const aiReviewLoadingId = ref(null)
const aiReviewTarget = ref(null)
const aiReview = ref(null)

const statusMap = { published: 'PUBLISHED', draft: 'DRAFT', pending: 'PENDING', rejected: 'REJECTED' }

const tabMeta = computed(() => ({
  all: { label: '全部文章', desc: '所有状态的文章与草稿' },
  published: { label: '已发布', desc: '前台可见的文章' },
  draft: { label: '草稿', desc: '尚未提交审核的内容' },
  pending: { label: '待审核', desc: '等待审核通过后发布' },
  rejected: { label: '已退回', desc: '需要按修改意见调整' }
}[activeTab.value] || { label: '全部文章', desc: '所有状态的文章与草稿' }))

function statusLabel(status) {
  return ({ PUBLISHED: '已发布', PENDING: '待审核', DRAFT: '草稿', REJECTED: '已退回' }[status] || status)
}

function params(page = posts.page) {
  const p = { page, size: posts.size }
  if (keyword.value.trim()) p.keyword = keyword.value.trim()
  if (statusMap[activeTab.value]) p.status = statusMap[activeTab.value]
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
    stats.value = (await http.get('/admin/stats')).data
  } catch (e) {
    /* 统计失败不阻塞 */
  }
}

function search() {
  load(1)
}

function openReview(post) {
  if (!post) return
  reviewTarget.value = post
  reviewNote.value = post.reviewNote || ''
}

async function submitReview(status) {
  if (!reviewTarget.value) return
  if (status === 'REJECTED' && !reviewNote.value.trim()) {
    toast('退回文章时请填写修改意见', 'error')
    return
  }
  reviewing.value = true
  try {
    await http.put(`/admin/posts/${reviewTarget.value.id}/review`, {
      status,
      note: reviewNote.value.trim()
    })
    toast(status === 'PUBLISHED' ? '文章已通过并发布' : status === 'REJECTED' ? '文章已退回修改' : '文章已转为草稿')
    reviewTarget.value = null
    load()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    reviewing.value = false
  }
}

async function reviewWithAi(post) {
  if (aiReviewLoadingId.value) return
  aiReviewLoadingId.value = post.id
  aiReviewTarget.value = post
  aiReview.value = null
  try {
    const detail = (await http.get(`/admin/posts/${post.id}`)).data
    aiReview.value = (await http.post('/admin/ai/review-article', {
      title: post.title,
      content: detail.content || ''
    }, { timeout: 90000 })).data
    aiReviewOpen.value = true
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    aiReviewLoadingId.value = null
  }
}

async function unpublish(post) {
  if (!window.confirm(`确定将《${post.title}》转为草稿并从前台隐藏吗？`)) return
  try {
    await http.put(`/admin/posts/${post.id}/review`, { status: 'DRAFT', note: '' })
    toast('文章已转为草稿，前台不再展示')
    load()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function removePost(post) {
  if (!window.confirm(`确定删除《${post.title}》吗？相关评论、点赞、收藏会一并删除，且不可恢复。`)) return
  try {
    await http.delete(`/admin/posts/${post.id}`)
    toast('文章已删除')
    load()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  }
}

watch(activeTab, () => load(1))
onMounted(() => {
  load(1)
  loadStats()
})
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">{{ tabMeta.label }}</span>
      <div class="adm-head-actions">
        <router-link class="btn btn-sm" to="/admin/posts/new">新建文章</router-link>
      </div>
    </div>

    <section class="adm-mini-stats">
      <div><span>已发布</span><strong>{{ stats?.posts ?? '—' }}</strong></div>
      <div><span>待审核</span><strong>{{ stats?.pendingPosts ?? '—' }}</strong></div>
      <div><span>草稿</span><strong>{{ stats?.drafts ?? '—' }}</strong></div>
      <div><span>已退回</span><strong>{{ stats?.rejectedPosts ?? '—' }}</strong></div>
    </section>

    <section class="adm-toolbar">
      <div class="adm-search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-4-4" />
        </svg>
        <input v-model="keyword" type="search" placeholder="搜索标题、摘要、标签或作者" @keyup.enter="search" />
        <button v-if="keyword" type="button" class="btn-text" @click="keyword = ''; search()">清除</button>
      </div>
      <button class="btn btn-ghost btn-sm" type="button" @click="search">搜索</button>
    </section>

    <div v-if="loading" class="adm-loading">正在读取文章…</div>

    <section v-else class="adm-card adm-table-card">
      <div class="adm-table-wrap">
        <table class="table adm-table adm-post-table">
          <thead>
            <tr>
              <th>文章</th>
              <th>状态</th>
              <th>作者</th>
              <th>阅读</th>
              <th>互动</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="post in posts.records" :key="post.id">
              <td class="adm-post-title-cell">
                <router-link class="adm-row-title" :to="`/admin/posts/${post.id}/edit`">{{ post.title }}</router-link>
                <span class="adm-row-meta">
                  {{ post.summary || '暂无摘要' }}
                </span>
                <span v-if="post.reviewNote" class="adm-review-note">修改意见：{{ post.reviewNote }}</span>
              </td>
              <td>
                <span class="adm-status" :class="`is-${String(post.status).toLowerCase()}`">{{ statusLabel(post.status) }}</span>
                <span v-if="post.top" class="adm-top-badge">置顶</span>
              </td>
              <td>{{ post.authorName || '未署名' }}</td>
              <td>{{ post.views }}</td>
              <td><span class="adm-inline-count">{{ post.likes }} 喜欢</span><span class="adm-inline-count">{{ post.comments }} 评论</span></td>
              <td>
                <span class="adm-date-main">{{ formatDate(post.updatedAt) }}</span>
                <span v-if="post.reviewedAt" class="adm-row-meta">审核 {{ formatDateTime(post.reviewedAt) }}</span>
              </td>
              <td>
                <div class="adm-row-actions wrap">
                  <router-link class="btn-text" :to="`/admin/posts/${post.id}/edit`">编辑</router-link>
                  <a class="btn-text" :href="`/post/${post.id}`" target="_blank" rel="noopener">预览</a>
                  <button class="btn-text" type="button" :disabled="aiReviewLoadingId === post.id" @click="reviewWithAi(post)">{{ aiReviewLoadingId === post.id ? '审核中…' : 'AI 审核' }}</button>
                  <button v-if="post.status !== 'PUBLISHED'" class="btn-text" type="button" @click="openReview(post)">{{ post.status === 'PENDING' ? '审核' : '发布' }}</button>
                  <button v-else class="btn-text" type="button" @click="unpublish(post)">下架</button>
                  <button class="btn-text danger" type="button" @click="removePost(post)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!posts.records.length">
              <td colspan="7" class="adm-empty-cell">没有符合条件的文章</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="posts.total > posts.size" class="pager">
        <button type="button" :disabled="posts.page <= 1" @click="load(posts.page - 1)">上一页</button>
        <span class="meta">第 {{ posts.page }} 页 / 共 {{ posts.total }} 篇</span>
        <button type="button" :disabled="posts.page * posts.size >= posts.total" @click="load(posts.page + 1)">下一页</button>
      </div>
    </section>

    <div v-if="reviewTarget" class="adm-modal-wrap" @click.self="reviewTarget = null">
      <div class="adm-modal">
        <div class="adm-modal-head">
          <div>
            <span class="adm-eyebrow">REVIEW / {{ statusLabel(reviewTarget.status) }}</span>
            <h2>审核《{{ reviewTarget.title }}》</h2>
          </div>
          <button type="button" class="adm-drawer-close" aria-label="关闭" @click="reviewTarget = null">×</button>
        </div>
        <p class="adm-note">审核通过后文章会立即出现在前台；退回时请给作者留下明确的修改意见。</p>
        <div class="field">
          <label for="reviewNote">修改意见 / 审核备注</label>
          <textarea id="reviewNote" v-model="reviewNote" maxlength="512" style="min-height: 110px" placeholder="通过时可以留空；退回时必填，例如：正文缺少小标题，封面图不清晰。"></textarea>
        </div>
        <div class="adm-modal-actions split">
          <button class="btn btn-ghost" type="button" :disabled="reviewing" @click="submitReview('REJECTED')">退回修改</button>
          <div>
            <button class="btn btn-ghost" type="button" :disabled="reviewing" @click="submitReview('DRAFT')">存为草稿</button>
            <button class="btn" type="button" :disabled="reviewing" @click="submitReview('PUBLISHED')">{{ reviewing ? '提交中…' : '通过并发布' }}</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="aiReviewOpen" class="adm-modal-wrap" @click.self="aiReviewOpen = false">
      <div class="adm-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI ARTICLE REVIEW</span><h2>《{{ aiReviewTarget?.title }}》审核结果</h2><p class="meta">AI 只给建议，是否通过仍由你决定。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="aiReviewOpen = false">×</button>
        </div>
        <div v-if="aiReview" class="ai-review-result compact">
          <div class="ai-review-score"><strong>{{ aiReview.score }}</strong><span>/ 100</span><em>{{ aiReview.verdict }}</em></div>
          <p class="ai-review-conclusion">{{ aiReview.conclusion }}</p>
          <div v-if="aiReview.issues?.length" class="ai-review-block">
            <h3>发现的问题</h3>
            <ul><li v-for="(item, i) in aiReview.issues" :key="i">{{ item }}</li></ul>
          </div>
          <div v-if="aiReview.suggestions?.length" class="ai-review-block">
            <h3>修改建议</h3>
            <ul><li v-for="(item, i) in aiReview.suggestions" :key="i">{{ item }}</li></ul>
          </div>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="aiReviewOpen = false">关闭</button>
          <button class="btn" type="button" @click="aiReviewOpen = false; openReview(aiReviewTarget)">进入人工审核</button>
        </div>
      </div>
    </div>
  </div>
</template>
