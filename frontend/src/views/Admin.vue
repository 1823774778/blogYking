<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api'
import { state, toast } from '../state'
import { formatDate } from '../utils'

const route = useRoute()
const stats = ref(null)
const recentPosts = ref([])
const attentionPosts = ref([])
const attentionComments = ref([])
const loading = ref(false)
const refreshing = ref(false)

const activeTab = computed(() => String(route.query.tab || 'overview'))
const attentionCount = computed(() =>
  Number(stats.value?.pendingPosts || 0) + Number(stats.value?.pendingComments || 0)
)
const pipeline = computed(() => {
  const published = Number(stats.value?.posts || 0)
  const pending = Number(stats.value?.pendingPosts || 0)
  const draft = Number(stats.value?.drafts || 0)
  const rejected = Number(stats.value?.rejectedPosts || 0)
  const total = Math.max(1, published + pending + draft + rejected)
  return [
    { key: 'published', label: '已发布', value: published, percent: Math.round(published / total * 100) },
    { key: 'pending', label: '待审核', value: pending, percent: Math.round(pending / total * 100) },
    { key: 'draft', label: '草稿', value: draft, percent: Math.round(draft / total * 100) },
    { key: 'rejected', label: '已退回', value: rejected, percent: Math.round(rejected / total * 100) }
  ]
})

async function loadStats(silent = false) {
  if (!silent) loading.value = true
  try {
    stats.value = (await http.get('/admin/stats')).data
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    if (!silent) loading.value = false
  }
}

async function loadRecent() {
  try {
    const res = await http.get('/admin/posts', { params: { page: 1, size: 6 } })
    recentPosts.value = res.data.records
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function loadAttention() {
  try {
    const [postsRes, commentsRes] = await Promise.all([
      http.get('/admin/posts', { params: { page: 1, size: 6, status: 'PENDING' } }),
      http.get('/admin/comments', { params: { page: 1, size: 6, status: 'PENDING' } })
    ])
    attentionPosts.value = postsRes.data.records
    attentionComments.value = commentsRes.data.records
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function refresh() {
  refreshing.value = true
  await Promise.all([loadStats(true), loadRecent(), loadAttention()])
  refreshing.value = false
}

watch(activeTab, (tab) => {
  if (tab === 'attention' && !attentionPosts.value.length) loadAttention()
})

onMounted(async () => {
  await Promise.all([loadStats(), loadRecent(), loadAttention()])
})
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">欢迎回来，{{ state.user?.nickname || state.user?.username }}</span>
      <div class="adm-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="refreshing" @click="refresh">
          {{ refreshing ? '刷新中…' : '刷新数据' }}
        </button>
        <router-link class="btn btn-sm" to="/admin/posts/new">新建文章</router-link>
      </div>
    </div>

    <div v-if="loading" class="adm-loading">正在读取站点数据…</div>

    <template v-if="activeTab === 'attention'">
      <section class="adm-attention-banner" :class="{ clear: attentionCount === 0 }">
        <div class="adm-attention-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M12 3 2.5 20h19L12 3Z" />
            <path d="M12 9v5M12 17v.2" />
          </svg>
        </div>
        <div>
          <strong>{{ attentionCount ? `${attentionCount} 项内容等待处理` : '今天没有待处理事项' }}</strong>
          <p>{{ attentionCount ? '审核通过后才会进入前台；退回时请写清楚修改意见。' : '文章与评论都已处理完毕，可以继续写作。' }}</p>
        </div>
        <router-link v-if="stats?.pendingPosts" class="btn btn-ghost btn-sm" to="/admin/posts?tab=pending">处理文章</router-link>
        <router-link v-if="stats?.pendingComments" class="btn btn-ghost btn-sm" to="/admin/comments?tab=pending">处理评论</router-link>
      </section>

      <div class="adm-split">
        <section class="adm-card">
          <div class="adm-card-head">
            <div>
              <span class="adm-card-kicker">POSTS</span>
              <h2>待审核文章</h2>
            </div>
            <router-link class="btn-text" to="/admin/posts?tab=pending">查看全部</router-link>
          </div>
          <div v-if="attentionPosts.length" class="adm-attention-list">
            <div v-for="post in attentionPosts" :key="post.id" class="adm-attention-row">
              <div>
                <router-link class="adm-row-title" :to="`/admin/posts/${post.id}/edit`">{{ post.title }}</router-link>
                <div class="adm-row-meta">{{ post.authorName || '未署名' }} · {{ formatDate(post.updatedAt) }}</div>
              </div>
              <router-link class="btn btn-ghost btn-sm" :to="`/admin/posts?tab=pending`">去审核</router-link>
            </div>
          </div>
          <div v-else class="adm-empty">没有待审核文章</div>
        </section>

        <section class="adm-card">
          <div class="adm-card-head">
            <div>
              <span class="adm-card-kicker">COMMENTS</span>
              <h2>待审核评论</h2>
            </div>
            <router-link class="btn-text" to="/admin/comments?tab=pending">查看全部</router-link>
          </div>
          <div v-if="attentionComments.length" class="adm-attention-list">
            <div v-for="comment in attentionComments" :key="comment.id" class="adm-attention-row">
              <div>
                <p class="adm-comment-quote">“{{ comment.content }}”</p>
                <div class="adm-row-meta">{{ comment.nickname || '匿名读者' }} · {{ comment.postTitle || '未知文章' }}</div>
              </div>
              <router-link class="btn btn-ghost btn-sm" to="/admin/comments?tab=pending">去审核</router-link>
            </div>
          </div>
          <div v-else class="adm-empty">没有待审核评论</div>
        </section>
      </div>
    </template>

    <template v-else>
      <section class="adm-stat-grid">
        <article class="adm-stat-card featured">
          <span class="adm-stat-index">01</span>
          <div class="adm-stat-label">已发布文章</div>
          <strong>{{ stats?.posts ?? '—' }}</strong>
          <span class="adm-stat-foot">前台可见内容</span>
        </article>
        <article class="adm-stat-card alert" :class="{ quiet: !stats?.pendingPosts }">
          <span class="adm-stat-index">02</span>
          <div class="adm-stat-label">待审核文章</div>
          <strong>{{ stats?.pendingPosts ?? '—' }}</strong>
          <span class="adm-stat-foot">{{ stats?.pendingPosts ? '需要处理' : '已清空' }}</span>
        </article>
        <article class="adm-stat-card">
          <span class="adm-stat-index">03</span>
          <div class="adm-stat-label">累计阅读</div>
          <strong>{{ stats?.totalViews ?? '—' }}</strong>
          <span class="adm-stat-foot">所有文章浏览</span>
        </article>
        <article class="adm-stat-card">
          <span class="adm-stat-index">04</span>
          <div class="adm-stat-label">评论总数</div>
          <strong>{{ stats?.comments ?? '—' }}</strong>
          <span class="adm-stat-foot">{{ stats?.pendingComments || 0 }} 条待审核</span>
        </article>
        <article class="adm-stat-card">
          <span class="adm-stat-index">05</span>
          <div class="adm-stat-label">注册用户</div>
          <strong>{{ stats?.users ?? '—' }}</strong>
          <span class="adm-stat-foot">{{ stats?.disabledUsers || 0 }} 个已封禁</span>
        </article>
        <article class="adm-stat-card">
          <span class="adm-stat-index">06</span>
          <div class="adm-stat-label">草稿</div>
          <strong>{{ stats?.drafts ?? '—' }}</strong>
          <span class="adm-stat-foot">尚未提交审核</span>
        </article>
        <article class="adm-stat-card">
          <span class="adm-stat-index">07</span>
          <div class="adm-stat-label">喜欢</div>
          <strong>{{ stats?.likes ?? '—' }}</strong>
          <span class="adm-stat-foot">{{ stats?.favorites || 0 }} 次收藏</span>
        </article>
        <article class="adm-stat-card">
          <span class="adm-stat-index">08</span>
          <div class="adm-stat-label">已禁言</div>
          <strong>{{ stats?.mutedUsers ?? '—' }}</strong>
          <span class="adm-stat-foot">{{ stats?.admins || 0 }} 位管理员</span>
        </article>
      </section>

      <div class="adm-split wide-left">
        <section class="adm-card">
          <div class="adm-card-head">
            <div>
              <span class="adm-card-kicker">RECENT</span>
              <h2>最近文章</h2>
            </div>
            <router-link class="btn-text" to="/admin/posts">全部文章</router-link>
          </div>
          <div class="adm-table-wrap">
            <table class="table adm-table">
              <thead>
                <tr>
                  <th>标题</th>
                  <th>状态</th>
                  <th>阅读</th>
                  <th>更新</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="post in recentPosts" :key="post.id">
                  <td class="adm-title-cell">
                    <router-link class="adm-row-title" :to="`/admin/posts/${post.id}/edit`">{{ post.title }}</router-link>
                    <span class="adm-row-meta">{{ post.authorName || '未署名' }}</span>
                  </td>
                  <td>
                    <span class="adm-status" :class="`is-${String(post.status).toLowerCase()}`">{{ post.status === 'PUBLISHED' ? '已发布' : post.status === 'PENDING' ? '待审核' : post.status === 'REJECTED' ? '已退回' : '草稿' }}</span>
                  </td>
                  <td>{{ post.views }}</td>
                  <td>{{ formatDate(post.updatedAt) }}</td>
                </tr>
                <tr v-if="!recentPosts.length">
                  <td colspan="4" class="adm-empty-cell">还没有文章，去写第一篇吧</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head">
              <div>
                <span class="adm-card-kicker">PIPELINE</span>
                <h2>内容流转</h2>
              </div>
            </div>
            <div class="adm-pipeline">
              <div v-for="item in pipeline" :key="item.key" class="adm-pipeline-row">
                <div class="adm-pipeline-meta">
                  <span>{{ item.label }}</span>
                  <b>{{ item.value }}</b>
                </div>
                <div class="adm-pipeline-track"><span :class="item.key" :style="{ width: Math.max(3, item.percent) + '%' }"></span></div>
              </div>
            </div>
          </section>

          <section class="adm-card adm-quick-card">
            <div class="adm-card-head">
              <div>
                <span class="adm-card-kicker">QUICK</span>
                <h2>快捷入口</h2>
              </div>
            </div>
            <div class="adm-quick-grid">
              <router-link to="/admin/posts/new"><span>写文章</span><b>→</b></router-link>
              <router-link to="/admin/comments?tab=pending"><span>审评论</span><b>→</b></router-link>
              <router-link to="/admin/site-info"><span>站点信息</span><b>→</b></router-link>
              <router-link to="/admin/settings"><span>站点设置</span><b>→</b></router-link>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>
