<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'
import { formatDateTime } from '../../utils'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'all'))
const keyword = ref('')
const comments = reactive({ records: [], total: 0, page: 1, size: 15 })
const stats = ref(null)
const loading = ref(false)
const actingId = ref(null)
const aiReviewOpen = ref(false)
const aiReviewLoading = ref(false)
const aiReviewTarget = ref(null)
const aiReview = ref(null)
const summaryOpen = ref(false)
const summaryLoading = ref(false)
const summaryTarget = ref(null)
const summaryText = ref('')

const statusMap = { pending: 'PENDING', visible: 'VISIBLE', hidden: 'HIDDEN' }

const tabMeta = computed(() => ({
  all: { label: '全部评论', desc: '所有读者评论与回复' },
  pending: { label: '待审核', desc: '审核通过后才会在前台显示' },
  visible: { label: '已展示', desc: '当前对读者可见的评论' },
  hidden: { label: '已隐藏', desc: '已从前台撤下的评论' }
}[activeTab.value] || { label: '全部评论', desc: '所有读者评论与回复' }))

function statusLabel(status) {
  return ({ VISIBLE: '已展示', PENDING: '待审核', HIDDEN: '已隐藏' }[status] || status)
}

function params(page = comments.page) {
  const p = { page, size: comments.size }
  if (keyword.value.trim()) p.keyword = keyword.value.trim()
  if (statusMap[activeTab.value]) p.status = statusMap[activeTab.value]
  return p
}

async function load(page = 1) {
  loading.value = true
  try {
    const res = await http.get('/admin/comments', { params: params(page) })
    comments.records = res.data.records
    comments.total = res.data.total
    comments.page = res.data.page
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

async function moderate(comment, status) {
  actingId.value = comment.id
  try {
    await http.put(`/admin/comments/${comment.id}/status`, { status })
    toast(status === 'VISIBLE' ? '评论已通过并展示' : status === 'HIDDEN' ? '评论已隐藏' : '评论状态已更新')
    load()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    actingId.value = null
  }
}

async function reviewWithAi(comment) {
  aiReviewLoading.value = true
  aiReviewTarget.value = comment
  aiReview.value = null
  try {
    aiReview.value = (await http.post('/admin/ai/review-comment', {
      postTitle: comment.postTitle || '',
      content: comment.content
    }, { timeout: 60000 })).data
    aiReviewOpen.value = true
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    aiReviewLoading.value = false
  }
}

async function applyAiReview() {
  const decision = aiReview.value?.decision
  if (!aiReviewTarget.value) return
  if (decision === 'APPROVE') {
    await moderate(aiReviewTarget.value, 'VISIBLE')
  } else if (decision === 'REJECT') {
    await moderate(aiReviewTarget.value, 'HIDDEN')
  } else {
    toast('AI 建议人工复核，已保留原状态')
  }
  aiReviewOpen.value = false
}

async function summarizePost(comment) {
  if (!comment.postId) return
  summaryLoading.value = true
  const key = `summary-${comment.postId}`
  actingId.value = key
  summaryTarget.value = comment
  summaryText.value = ''
  try {
    const res = await http.post(`/admin/posts/${comment.postId}/ai-comment-summary`, null, { timeout: 90000 })
    summaryText.value = res.data.commentSummary || ''
    summaryOpen.value = true
    toast('评论总结已生成并保存到文章')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    summaryLoading.value = false
    actingId.value = null
  }
}

async function removeComment(comment) {
  if (!window.confirm('确定删除这条评论吗？如果它下面有一级回复，会一并删除。')) return
  try {
    await http.delete(`/comments/${comment.id}`)
    toast('评论已删除')
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
      <span class="adm-total-pill">{{ tabMeta.label }} · 共 {{ comments.total }} 条</span>
    </div>

    <section class="adm-mini-stats">
      <div><span>评论总数</span><strong>{{ stats?.comments ?? '—' }}</strong></div>
      <div><span>待审核</span><strong>{{ stats?.pendingComments ?? '—' }}</strong></div>
      <div><span>已隐藏</span><strong>{{ stats?.hiddenComments ?? '—' }}</strong></div>
      <div>
        <span>审核开关</span>
        <strong class="adm-mini-state" :class="stats?.commentReview === 'true' ? 'is-on' : 'is-off'">
          {{ stats ? (stats.commentReview === 'true' ? '已开启' : '未开启') : '—' }}
        </strong>
      </div>
    </section>

    <section class="adm-toolbar">
      <div class="adm-search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-4-4" />
        </svg>
        <input v-model="keyword" type="search" placeholder="搜索评论内容或评论者昵称" @keyup.enter="search" />
        <button v-if="keyword" type="button" class="btn-text" @click="keyword = ''; search()">清除</button>
      </div>
      <button class="btn btn-ghost btn-sm" type="button" @click="search">搜索</button>
    </section>

    <div v-if="loading" class="adm-loading">正在读取评论…</div>

    <section v-else class="adm-card adm-table-card">
      <div class="adm-table-wrap">
        <table class="table adm-table adm-comment-table">
          <thead>
            <tr>
              <th>评论内容</th>
              <th>评论者</th>
              <th>所属文章</th>
              <th>状态</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="comment in comments.records" :key="comment.id">
              <td class="adm-comment-cell">
                <p>“{{ comment.content }}”</p>
                <span v-if="comment.parentId" class="adm-row-meta">回复评论 #{{ comment.parentId }}</span>
              </td>
              <td>
                <span class="adm-comment-user">{{ comment.nickname || '匿名读者' }}</span>
                <span class="adm-row-meta">ID {{ comment.userId }}</span>
              </td>
              <td>
                <router-link v-if="comment.postTitle" class="adm-post-link" :to="`/post/${comment.postId}`" target="_blank">{{ comment.postTitle }}</router-link>
                <span v-else class="adm-muted-text">文章已删除</span>
              </td>
              <td>
                <span class="adm-status" :class="`is-${String(comment.status).toLowerCase()}`">{{ statusLabel(comment.status) }}</span>
              </td>
              <td>{{ formatDateTime(comment.createdAt) }}</td>
              <td>
                <div class="adm-row-actions wrap">
                  <button v-if="comment.status !== 'VISIBLE'" class="btn-text" type="button" :disabled="actingId === comment.id" @click="moderate(comment, 'VISIBLE')">通过</button>
                  <button v-if="comment.status === 'VISIBLE'" class="btn-text" type="button" :disabled="actingId === comment.id" @click="moderate(comment, 'HIDDEN')">隐藏</button>
                  <button v-if="comment.status === 'PENDING'" class="btn-text" type="button" :disabled="actingId === comment.id" @click="moderate(comment, 'HIDDEN')">拒绝</button>
                  <button class="btn-text" type="button" :disabled="aiReviewLoading" @click="reviewWithAi(comment)">AI 审核</button>
                  <button v-if="comment.postId" class="btn-text" type="button" :disabled="summaryLoading" @click="summarizePost(comment)">AI 总结评论</button>
                  <button class="btn-text danger" type="button" @click="removeComment(comment)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!comments.records.length">
              <td colspan="6" class="adm-empty-cell">没有符合条件的评论</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="comments.total > comments.size" class="pager">
        <button type="button" :disabled="comments.page <= 1" @click="load(comments.page - 1)">上一页</button>
        <span class="meta">第 {{ comments.page }} 页 / 共 {{ comments.total }} 条</span>
        <button type="button" :disabled="comments.page * comments.size >= comments.total" @click="load(comments.page + 1)">下一页</button>
      </div>
    </section>

    <div v-if="aiReviewOpen" class="adm-modal-wrap" @click.self="aiReviewOpen = false">
      <div class="adm-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI COMMENT REVIEW</span><h2>AI 评论审核</h2><p class="meta">仅针对广告、攻击、隐私和违法内容，不因观点不同而拒绝。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="aiReviewOpen = false">×</button>
        </div>
        <div v-if="aiReview" class="ai-review-result compact">
          <div class="ai-decision-row">
            <span class="adm-status" :class="aiReview.decision === 'APPROVE' ? 'is-published' : aiReview.decision === 'REJECT' ? 'is-rejected' : 'is-pending'">
              {{ aiReview.decision === 'APPROVE' ? '建议通过' : aiReview.decision === 'REJECT' ? '建议拒绝' : '建议人工复核' }}
            </span>
            <span class="meta">风险：{{ aiReview.risk }}</span>
          </div>
          <p class="ai-review-conclusion">{{ aiReview.reason }}</p>
          <p class="adm-note">{{ aiReview.suggestion }}</p>
          <blockquote class="ai-review-quote">{{ aiReviewTarget?.content }}</blockquote>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="aiReviewOpen = false">保留原状态</button>
          <button class="btn" type="button" @click="applyAiReview">采纳建议</button>
        </div>
      </div>
    </div>

    <div v-if="summaryOpen" class="adm-modal-wrap" @click.self="summaryOpen = false">
      <div class="adm-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI SUMMARY</span><h2>评论区总结</h2><p class="meta">已保存到文章，会展示在前台文章详情评论区上方。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" @click="summaryOpen = false">×</button>
        </div>
        <p class="ai-summary-text">{{ summaryText || '暂无总结' }}</p>
        <p class="meta">文章：{{ summaryTarget?.postTitle || '—' }}</p>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" @click="summaryOpen = false">关闭</button>
          <router-link class="btn" :to="`/post/${summaryTarget?.postId}`" target="_blank">查看文章</router-link>
        </div>
      </div>
    </div>
  </div>
</template>
