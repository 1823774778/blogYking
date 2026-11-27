<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { hasPermission, toast } from '../../state'
import { formatDateTime } from '../../utils'
import AiThinking from '../../components/AiThinking.vue'

const route = useRoute()
const canModerate = computed(() => hasPermission('system:comments'))
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
const aiReviewPromptOpen = ref(false)
const aiReviewPrompt = ref('')
const aiCommentEnabled = ref(true)
const commentReviewEnabled = ref(false)
const commentEnabled = ref(true)
const reviewSaving = ref(false)
const commentToggleSaving = ref(false)
const selectedIds = ref([])
const bulkSaving = ref(false)
const defaultPrompts = reactive({ review: '' })

const statusMap = { pending: 'PENDING', visible: 'VISIBLE', hidden: 'HIDDEN' }
const allSelected = computed(() => comments.records.length > 0 && selectedIds.value.length === comments.records.length)
const selectedCount = computed(() => selectedIds.value.length)

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
    selectedIds.value = []
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = (await http.get('/admin/stats')).data
    if (stats.value) {
      commentEnabled.value = String(stats.value.commentEnabled ?? 'true') === 'true'
    }
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

async function loadAiConfig() {
  if (!canModerate.value) return
  try {
    const cfg = (await http.get('/admin/config')).data || {}
    aiCommentEnabled.value = String(cfg.aiCommentEnabled ?? 'true') === 'true'
    commentReviewEnabled.value = String(cfg.commentReview ?? 'false') === 'true'
    commentEnabled.value = String(cfg.commentEnabled ?? 'true') === 'true'
    defaultPrompts.review = cfg.aiCommentReviewPrompt || ''
    aiReviewPrompt.value = defaultPrompts.review
  } catch {
    /* 开关由后端兜底 */
  }
}

function openAiReview(comment) {
  if (!aiCommentEnabled.value) {
    toast('AI 评论功能未开启，请先在站点设置中开启 AI 评论', 'error')
    return
  }
  aiReviewTarget.value = comment
  aiReviewPrompt.value = aiReviewPrompt.value || defaultPrompts.review
  aiReviewPromptOpen.value = true
}

function closeCommentReviewPrompt() {
  if (aiReviewLoading.value) return
  aiReviewPromptOpen.value = false
}

async function reviewWithAi(comment) {
  if (!aiReviewPrompt.value.trim()) {
    toast('请填写 AI 审核评论提示词', 'error')
    return
  }
  aiReviewLoading.value = true
  aiReviewTarget.value = comment
  aiReview.value = null
  try {
    aiReview.value = (await http.post('/admin/ai/review-comment', {
      postTitle: comment.postTitle || '',
      content: comment.content,
      prompt: aiReviewPrompt.value.trim()
    }, { timeout: 60000 })).data
    aiReviewPromptOpen.value = false
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

function toggleSelectAll(event) {
  selectedIds.value = event.target.checked ? comments.records.map((item) => item.id) : []
}

function toggleSelect(comment) {
  if (selectedIds.value.includes(comment.id)) {
    selectedIds.value = selectedIds.value.filter((id) => id !== comment.id)
  } else {
    selectedIds.value = [...selectedIds.value, comment.id]
  }
}

async function saveCommentReview(event) {
  const next = Boolean(event.target.checked)
  reviewSaving.value = true
  try {
    const res = (await http.put('/admin/config', { commentReview: next ? 'true' : 'false' })).data || {}
    commentReviewEnabled.value = String(res.commentReview ?? (next ? 'true' : 'false')) === 'true'
    if (stats.value) stats.value = { ...stats.value, commentReview: commentReviewEnabled.value ? 'true' : 'false' }
    toast(commentReviewEnabled.value ? '已开启评论先审后发' : '已关闭评论先审后发，新评论将直接展示')
  } catch (e) {
    event.target.checked = commentReviewEnabled.value
    toast(e.message, 'error')
  } finally {
    reviewSaving.value = false
  }
}

async function saveCommentEnabled(event) {
  const noComments = Boolean(event.target.checked)
  commentToggleSaving.value = true
  try {
    const res = (await http.put('/admin/config', {
      commentEnabled: noComments ? 'false' : 'true'
    })).data || {}
    commentEnabled.value = String(res.commentEnabled ?? (noComments ? 'false' : 'true')) === 'true'
    if (stats.value) {
      stats.value = { ...stats.value, commentEnabled: commentEnabled.value ? 'true' : 'false' }
    }
    toast(commentEnabled.value ? '已恢复评论功能' : '已开启无需评论，前台文章隐藏评论区并停止接收新评论')
  } catch (e) {
    event.target.checked = !commentEnabled.value
    toast(e.message, 'error')
  } finally {
    commentToggleSaving.value = false
  }
}

async function bulkModerate(status) {
  if (!selectedIds.value.length) return
  const label = status === 'VISIBLE' ? '通过' : '隐藏'
  if (!window.confirm(`确定批量${label}选中的 ${selectedIds.value.length} 条评论吗？`)) return
  bulkSaving.value = true
  try {
    const res = await http.put('/admin/comments/bulk-status', { ids: selectedIds.value, status })
    toast(`已${label} ${res.data?.updated ?? selectedIds.value.length} 条评论`)
    selectedIds.value = []
    load()
    loadStats()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    bulkSaving.value = false
  }
}

watch(activeTab, () => load(1))
onMounted(() => {
  load(1)
  loadStats()
  loadAiConfig()
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
      <div v-if="canModerate">
        <span>审核开关</span>
        <strong class="adm-mini-state" :class="stats?.commentReview === 'true' ? 'is-on' : 'is-off'">
          {{ stats ? (stats.commentReview === 'true' ? '已开启' : '未开启') : '—' }}
        </strong>
      </div>
    </section>

    <section v-if="canModerate" class="adm-card comment-policy-card">
      <div>
        <span class="adm-card-kicker">COMMENT POLICY</span>
        <h2>评论先审后发</h2>
        <p>开启后，新评论进入待审核状态，通过后才在前台展示；关闭后新评论直接公开。开关即时保存，已有评论不受影响。</p>
      </div>
      <label class="adm-switch-label comment-policy-switch">
        <span>{{ commentReviewEnabled ? '审核已开启' : '直接展示' }}</span>
        <input type="checkbox" :checked="commentReviewEnabled" :disabled="reviewSaving" @change="saveCommentReview" />
        <i class="adm-switch" aria-hidden="true"></i>
      </label>
    </section>

    <section v-if="canModerate" class="adm-card comment-policy-card comment-no-card">
      <div>
        <span class="adm-card-kicker">NO COMMENT</span>
        <h2>无需评论</h2>
        <p>开启后，前台文章不再展示评论区，也不能提交新评论；已有评论会保留，关闭开关即可恢复。</p>
      </div>
      <label class="adm-switch-label comment-policy-switch">
        <span>{{ commentEnabled ? '评论正常开放' : '无需评论' }}</span>
        <input type="checkbox" :checked="!commentEnabled" :disabled="commentToggleSaving" @change="saveCommentEnabled" />
        <i class="adm-switch" aria-hidden="true"></i>
      </label>
    </section>

    <div v-if="canModerate && selectedCount" class="adm-bulkbar">
      <span>已选 {{ selectedCount }} 条评论</span>
      <div class="adm-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="bulkSaving" @click="bulkModerate('VISIBLE')">批量通过</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="bulkSaving" @click="bulkModerate('HIDDEN')">批量隐藏</button>
        <button class="btn-text" type="button" :disabled="bulkSaving" @click="selectedIds = []">取消选择</button>
      </div>
    </div>

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
              <th v-if="canModerate" class="adm-check-col"><input type="checkbox" :checked="allSelected" aria-label="全选当前页评论" @change="toggleSelectAll" /></th>
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
              <td v-if="canModerate" class="adm-check-col"><input type="checkbox" :checked="selectedIds.includes(comment.id)" :aria-label="`选择评论 ${comment.id}`" @change="toggleSelect(comment)" /></td>
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
                  <button v-if="canModerate && comment.status !== 'VISIBLE'" class="btn-text" type="button" :disabled="actingId === comment.id" @click="moderate(comment, 'VISIBLE')">通过</button>
                  <button v-if="canModerate && comment.status === 'VISIBLE'" class="btn-text" type="button" :disabled="actingId === comment.id" @click="moderate(comment, 'HIDDEN')">隐藏</button>
                  <button v-if="canModerate && comment.status === 'PENDING'" class="btn-text" type="button" :disabled="actingId === comment.id" @click="moderate(comment, 'HIDDEN')">拒绝</button>
                  <button v-if="canModerate" class="btn-text" type="button" :disabled="aiReviewLoading || !aiCommentEnabled" @click="openAiReview(comment)">AI 审核</button>
                  <button class="btn-text danger" type="button" @click="removeComment(comment)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!comments.records.length">
              <td :colspan="canModerate ? 7 : 6" class="adm-empty-cell">没有符合条件的评论</td>
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

    <div v-if="aiReviewPromptOpen" class="adm-modal-wrap" @click.self="closeCommentReviewPrompt">
      <div class="adm-modal is-wide">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI COMMENT</span><h2>AI 审核评论</h2><p class="meta">提示词默认读取站点设置，本次修改只影响这次审核。</p></div>
          <button class="adm-drawer-close" type="button" aria-label="关闭" :disabled="aiReviewLoading" @click="closeCommentReviewPrompt">×</button>
        </div>
        <AiThinking
          v-if="aiReviewLoading"
          tone="amber"
          title="AI 正在审核评论"
          :steps="['读取评论与上下文', '检查广告、攻击与隐私风险', '判断是否适合公开展示', '整理审核结论']"
          note="审核结果返回后才会打开结果面板，弹窗会保持思考状态。"
        />
        <div v-else class="field">
          <label for="commentReviewPrompt">审核提示词</label>
          <textarea id="commentReviewPrompt" v-model="aiReviewPrompt" maxlength="4000" style="min-height: 150px"></textarea>
        </div>
        <div class="adm-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="aiReviewLoading" @click="closeCommentReviewPrompt">取消</button>
          <button class="btn" type="button" :disabled="aiReviewLoading" @click="reviewWithAi(aiReviewTarget)">{{ aiReviewLoading ? '思考中…' : '开始审核' }}</button>
        </div>
      </div>
    </div>

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

  </div>
</template>

<style scoped>
.comment-policy-card { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 14px; padding: 18px 20px; }
.comment-policy-card h2 { margin: 5px 0 4px; font-size: 18px; color: var(--ink); }
.comment-policy-card > div { flex: 1; min-width: 0; }
.comment-policy-card p { max-width: 760px; margin: 0; color: var(--muted); font-size: 12px; line-height: 1.65; }
.comment-policy-switch { flex: 0 0 220px; width: 220px; min-width: 220px; }
.adm-bulkbar { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 12px; padding: 10px 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--paper); color: var(--ink-soft); font-size: 12px; }
.adm-check-col { width: 34px; text-align: center; }
.adm-check-col input { width: 15px; height: 15px; accent-color: var(--accent); cursor: pointer; }
@media (max-width: 720px) { .comment-policy-card { align-items: flex-start; flex-direction: column; } .comment-policy-switch { flex: 0 0 auto; width: 100%; } .adm-bulkbar { align-items: flex-start; flex-direction: column; } }
</style>
