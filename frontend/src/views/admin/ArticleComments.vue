<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../../api'
import { toast } from '../../state'
import { formatDateTime, initialOf } from '../../utils'

const route = useRoute()
const router = useRouter()

const statusOptions = [
  { key: 'all', label: '全部评论' },
  { key: 'pending', label: '待审核' },
  { key: 'visible', label: '已展示' },
  { key: 'hidden', label: '已隐藏' }
]
const statusMap = { pending: 'PENDING', visible: 'VISIBLE', hidden: 'HIDDEN' }

const validStatus = (value) => statusOptions.some((item) => item.key === value) ? value : 'all'
const activeStatus = ref(validStatus(String(route.query.tab || 'all')))
const keyword = ref('')
const loading = ref(false)
const comments = reactive({ records: [], total: 0, page: 1, size: 12 })
const stats = ref(null)

function statusLabel(status) {
  return ({ VISIBLE: '已展示', PENDING: '待审核', HIDDEN: '已隐藏' }[status] || status)
}

function params(page = comments.page) {
  const p = { page, size: comments.size, mine: true }
  if (keyword.value.trim()) p.keyword = keyword.value.trim()
  if (statusMap[activeStatus.value]) p.status = statusMap[activeStatus.value]
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
    stats.value = (await http.get('/admin/stats', { params: { mine: true } })).data
  } catch {
    /* 统计失败不阻塞评论列表 */
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
  <div class="adm-page article-comments-page">
    <header class="content-hero comments-hero">
      <div>
        <span class="content-kicker">READER LETTERS</span>
        <h1>文章评论</h1>
        <p>这里汇总你名下所有文章收到的评论，包含其他读者的留言。按文章归集，不再只显示你亲自写下的评论。</p>
      </div>
      <span class="comments-seal">仅自己可见</span>
    </header>

    <section class="content-stats comments-stats" aria-label="文章评论统计">
      <article>
        <span>全部评论</span>
        <strong>{{ stats?.comments ?? '—' }}</strong>
        <small>你名下文章收到的评论</small>
      </article>
      <article>
        <span>待审核</span>
        <strong>{{ stats?.pendingComments ?? '—' }}</strong>
        <small>尚未公开的读者留言</small>
      </article>
      <article>
        <span>已隐藏</span>
        <strong>{{ stats?.hiddenComments ?? '—' }}</strong>
        <small>被你或管理员隐藏</small>
      </article>
    </section>

    <section class="content-toolbar">
      <div class="content-filters" role="tablist" aria-label="评论状态筛选">
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
        <input v-model="keyword" type="search" placeholder="搜索评论内容或读者昵称" @keyup.enter="search" />
        <button v-if="keyword" type="button" class="btn-text" @click="keyword = ''; search()">清除</button>
        <button type="button" class="btn btn-sm" @click="search">搜索</button>
      </div>
    </section>

    <div v-if="loading" class="adm-loading">正在整理你文章下的评论…</div>

    <section v-else class="comment-letters">
      <article v-for="comment in comments.records" :key="comment.id" class="comment-letter">
        <div class="comment-letter-head">
          <span class="comment-avatar">
            <img v-if="comment.avatar" :src="comment.avatar" alt="" />
            <span v-else>{{ initialOf(comment.nickname || '读者') }}</span>
          </span>
          <div class="comment-reader">
            <strong>{{ comment.nickname || '匿名读者' }}</strong>
            <span>{{ formatDateTime(comment.createdAt) }}</span>
          </div>
          <span class="adm-status" :class="`is-${String(comment.status).toLowerCase()}`">{{ statusLabel(comment.status) }}</span>
          <span v-if="comment.mine" class="comment-self-mark">我</span>
        </div>
        <blockquote class="comment-letter-body">{{ comment.content }}</blockquote>
        <div class="comment-letter-foot">
          <span>评论于</span>
          <router-link :to="`/post/${comment.postId}`" target="_blank">
            《{{ comment.postTitle || '未命名文章' }}》
          </router-link>
          <span v-if="comment.parentId" class="comment-reply-mark">回复评论</span>
          <router-link class="comment-open" :to="`/post/${comment.postId}`" target="_blank">查看文章</router-link>
        </div>
      </article>

      <div v-if="!comments.records.length" class="content-empty">
        <strong>还没有收到评论</strong>
        <span>当你发布文章后，读者的留言会按文章汇总到这里。</span>
      </div>
    </section>

    <div v-if="!loading && comments.total > comments.size" class="pager content-pager">
      <button type="button" :disabled="comments.page <= 1" @click="load(comments.page - 1)">上一页</button>
      <span class="meta">第 {{ comments.page }} 页 / 共 {{ comments.total }} 条</span>
      <button type="button" :disabled="comments.page * comments.size >= comments.total" @click="load(comments.page + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.article-comments-page {
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
    radial-gradient(circle at 90% 8%, rgba(210, 95, 0, 0.10), transparent 34%),
    var(--adm-card);
  box-shadow: var(--adm-shadow);
}

.comments-hero {
  background:
    radial-gradient(circle at 90% 8%, rgba(210, 95, 0, 0.11), transparent 34%),
    var(--adm-card);
}

.content-kicker {
  display: block;
  margin-bottom: 8px;
  color: var(--adm-amber, #d25f00);
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
  max-width: 650px;
  margin: 10px 0 0;
  color: var(--adm-chrome-muted);
  font-size: 14px;
  line-height: 1.7;
}

.comments-seal {
  flex: none;
  padding: 7px 11px;
  border: 1px solid var(--adm-line-strong);
  border-radius: 999px;
  color: var(--adm-chrome-muted);
  font-size: 11px;
  letter-spacing: 0.06em;
}

.content-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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
  background: var(--adm-amber-bg, #fff4e8);
  color: var(--adm-amber, #d25f00);
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

.comment-letters {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.comment-letter {
  position: relative;
  padding: 20px 22px 16px;
  overflow: hidden;
  border: 1px solid var(--adm-line);
  border-radius: 14px;
  background: var(--adm-card);
  box-shadow: var(--adm-shadow-soft);
}

.comment-letter::after {
  content: "“";
  position: absolute;
  right: 18px;
  bottom: -16px;
  color: var(--adm-amber, #d25f00);
  font-family: Georgia, serif;
  font-size: 72px;
  line-height: 1;
  opacity: 0.09;
  pointer-events: none;
}

.comment-letter-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.comment-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex: none;
  overflow: hidden;
  border-radius: 50%;
  background: var(--adm-amber-bg, #fff4e8);
  color: var(--adm-amber, #d25f00);
  font-size: 13px;
  font-weight: 700;
}

.comment-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.comment-reader {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.comment-reader strong {
  color: var(--adm-chrome-fg);
  font-size: 13px;
}

.comment-reader span {
  color: var(--adm-chrome-subtle);
  font-size: 11px;
}

.comment-self-mark {
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--adm-chrome-active-bg);
  color: var(--adm-chrome-active-fg);
  font-size: 10px;
  font-weight: 700;
}

.comment-letter-body {
  margin: 12px 0 12px;
  padding: 0;
  color: var(--adm-chrome-fg);
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.comment-letter-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding-top: 10px;
  border-top: 1px dashed var(--adm-line);
  color: var(--adm-chrome-subtle);
  font-size: 11px;
}

.comment-letter-foot a {
  color: var(--adm-chrome-muted);
  text-decoration: none;
}

.comment-letter-foot a:hover {
  color: var(--adm-amber, #d25f00);
}

.comment-reply-mark {
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--adm-chrome-hover);
  color: var(--adm-chrome-muted);
}

.comment-open {
  margin-left: auto;
  font-weight: 600;
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

@media (max-width: 900px) {
  .content-stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .comment-letter {
    padding: 18px 17px 14px;
  }
}

@media (max-width: 640px) {
  .content-hero {
    align-items: flex-start;
    flex-direction: column;
    padding: 22px 20px;
  }

  .content-stats {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .content-stats article {
    min-height: 84px;
    padding: 12px 14px;
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

  .comment-letter-head {
    gap: 8px;
  }

  .comment-open {
    margin-left: 0;
  }
}
</style>
