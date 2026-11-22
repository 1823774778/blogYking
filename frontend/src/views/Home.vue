<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { state, toast } from '../state'
import { formatDate, formatCount, initialOf, splitTags } from '../utils'
import PostCard from '../components/PostCard.vue'
import Pagination from '../components/Pagination.vue'
import MarkdownBody from '../components/MarkdownBody.vue'

const route = useRoute()
const router = useRouter()

const posts = ref([])
const total = ref(0)
const page = ref(1)
const size = 9
const keyword = ref(route.query.keyword ? String(route.query.keyword) : '')
const activeTag = ref(route.query.tag ? String(route.query.tag) : '')
const sort = ref('latest')
const loading = ref(false)

const tags = ref([])
const hot = ref([])
const stats = ref(null)

const featured = computed(() =>
  page.value === 1 && !activeTag.value && !keyword.value.trim() ? posts.value[0] || null : null
)
const gridPosts = computed(() => (featured.value ? posts.value.slice(1) : posts.value))
const announcementVisible = computed(() =>
  String(state.site.announcementEnabled).toLowerCase() === 'true' && String(state.site.announcement || '').trim()
)

async function load() {
  loading.value = true
  try {
    const res = await http.get('/posts', {
      params: {
        keyword: keyword.value.trim() || undefined,
        tag: activeTag.value || undefined,
        sort: sort.value,
        page: page.value,
        size
      }
    })
    posts.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function loadSide() {
  try {
    const [tagsRes, hotRes, statsRes] = await Promise.all([
      http.get('/posts/tags'),
      http.get('/posts/hot', { params: { limit: 5 } }),
      http.get('/posts/stats')
    ])
    tags.value = tagsRes.data
    hot.value = hotRes.data
    stats.value = statsRes.data
  } catch (e) {
    /* 侧栏加载失败不影响主列表 */
  }
}

function search() {
  page.value = 1
  const query = {}
  if (activeTag.value) query.tag = activeTag.value
  if (keyword.value.trim()) query.keyword = keyword.value.trim()
  router.replace({ path: '/', query })
  load()
}

function pickTag(tag) {
  activeTag.value = activeTag.value === tag ? '' : tag
  page.value = 1
  if (activeTag.value) {
    router.replace({ path: '/', query: { tag: activeTag.value } })
  } else {
    router.replace({ path: '/' })
  }
  load()
}

function clearFilters() {
  keyword.value = ''
  activeTag.value = ''
  page.value = 1
  router.replace({ path: '/' })
  load()
}

function setSort(value) {
  if (sort.value === value) return
  sort.value = value
  page.value = 1
  load()
}

function changePage(p) {
  page.value = p
  load()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

watch(
  () => route.query.keyword,
  (value) => {
    const next = value ? String(value) : ''
    if (next !== keyword.value) {
      keyword.value = next
      page.value = 1
      load()
    }
  }
)

watch(
  () => route.query.tag,
  (value) => {
    const next = value ? String(value) : ''
    if (next !== activeTag.value) {
      activeTag.value = next
      page.value = 1
      load()
    }
  }
)

onMounted(() => {
  load()
  loadSide()
})
</script>

<template>
  <section class="ff-hero">
    <div class="ff-planet" aria-hidden="true"></div>
    <div class="ff-hero-inner">
      <div class="ff-hero-copy">
        <span class="ff-hero-eyebrow">WELCOME TO</span>
        <h1>{{ state.site.heroTitle || state.site.siteName }}</h1>
        <p class="ff-hero-sub">{{ state.site.heroSubtitle || state.site.siteDesc }}</p>
        <div class="ff-hero-social">
          <a v-if="state.site.github" :href="state.site.github" target="_blank" rel="noopener">GitHub</a>
          <a v-if="state.site.email" :href="'mailto:' + state.site.email">邮箱</a>
          <router-link to="/about">关于本站</router-link>
        </div>
        <div class="ff-hero-search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-4-4" />
          </svg>
          <input v-model="keyword" type="search" placeholder="搜索文章…" @keyup.enter="search" />
          <button type="button" aria-label="搜索" @click="search">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </button>
        </div>
      </div>

      <router-link v-if="featured" class="ff-feature-card" :to="`/post/${featured.id}`">
        <div class="ff-feature-cover">
          <img v-if="featured.cover" :src="featured.cover" :alt="featured.title" />
          <span v-else class="ff-feature-placeholder" aria-hidden="true"></span>
          <span v-if="featured.top" class="ff-top-flag">置顶</span>
        </div>
        <div class="ff-feature-body">
          <span class="ff-feature-kicker">精选 · {{ splitTags(featured.tags)[0] || '随笔' }}</span>
          <h2>{{ featured.title }}</h2>
          <p>{{ featured.summary }}</p>
          <div class="ff-post-byline">
            <span class="ff-post-avatar">{{ initialOf(featured.authorName || '站长') }}</span>
            <span class="ff-post-author">{{ featured.authorName || '站长' }}</span>
            <span class="ff-post-date">发布于 {{ formatDate(featured.createdAt) }}</span>
          </div>
        </div>
      </router-link>

      <div v-else class="ff-feature-card is-empty">
        <div class="ff-feature-body">
          <span class="ff-feature-kicker">第一篇文章正在路上</span>
          <h2>这里还没有内容</h2>
          <p>{{ state.site.about || '从一个标题开始，慢慢把想法写下来。' }}</p>
        </div>
      </div>
    </div>
  </section>

  <section v-if="announcementVisible" class="ff-announcement">
    <div class="shell ff-announcement-inner">
      <span class="ff-announcement-mark" aria-hidden="true">!</span>
      <div class="ff-announcement-copy">
        <strong>{{ state.site.announcementTitle || '站点公告' }}</strong>
        <MarkdownBody :content="state.site.announcement" />
      </div>
      <a v-if="state.site.announcementLink" class="ff-announcement-link" :href="state.site.announcementLink">查看详情 →</a>
    </div>
  </section>

  <div class="ff-home">
    <main class="ff-feed">
      <div class="ff-feed-head">
        <div class="ff-feed-tabs">
          <button type="button" :class="{ on: sort === 'latest' }" @click="setSort('latest')">最新</button>
          <button type="button" :class="{ on: sort === 'hot' }" @click="setSort('hot')">热门</button>
        </div>
        <div class="ff-feed-tags">
          <button
            v-for="tag in tags.slice(0, 4)"
            :key="tag.name"
            class="tag"
            :class="{ active: activeTag === tag.name }"
            type="button"
            @click="pickTag(tag.name)"
          >
            #{{ tag.name }}
          </button>
        </div>
        <span class="ff-feed-meta">{{ total }} 篇文章</span>
      </div>

      <div v-if="activeTag || keyword.trim()" class="ff-filter-note">
        <span>
          正在浏览
          <template v-if="activeTag">标签「{{ activeTag }}」</template>
          <template v-if="activeTag && keyword.trim()"> · </template>
          <template v-if="keyword.trim()">关键词「{{ keyword.trim() }}」</template>
        </span>
        <button class="btn-text btn-sm" type="button" @click="clearFilters">清除筛选</button>
      </div>

      <div v-if="loading" class="ff-loading">正在翻页…</div>
      <div v-else-if="!posts.length" class="ff-empty">
        这里还没有文章。<br />换个关键词，或者从标签里找找。
      </div>

      <div v-else class="ff-post-grid">
        <PostCard v-for="post in gridPosts" :key="post.id" :post="post" />
      </div>

      <Pagination :total="total" :page="page" :size="size" @change="changePage" />
    </main>

    <aside class="ff-widgets">
      <section class="ff-widget">
        <h4 class="ff-widget-title">
          <span class="ff-wicon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 11v5M12 7.5v.5" />
            </svg>
          </span>
          关于这里
        </h4>
        <p class="ff-announce">{{ state.site.about || state.site.siteDesc }}</p>
        <router-link class="ff-widget-link" to="/about">了解更多 →</router-link>
      </section>

      <section class="ff-widget">
        <h4 class="ff-widget-title">
          <span class="ff-wicon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M20 13 13 20a2 2 0 0 1-2.8 0L4 13.8V4h9.8L20 10.2a2 2 0 0 1 0 2.8Z" />
              <circle cx="8.5" cy="8.5" r="1.4" />
            </svg>
          </span>
          标签
          <span class="ff-widget-count">{{ tags.length }}</span>
        </h4>
        <div class="ff-tag-cloud">
          <button
            v-for="tag in tags"
            :key="tag.name"
            class="tag"
            :class="{ active: activeTag === tag.name }"
            type="button"
            @click="pickTag(tag.name)"
          >
            #{{ tag.name }} <sup>{{ tag.count }}</sup>
          </button>
          <span v-if="!tags.length" class="count">暂无标签</span>
        </div>
      </section>

      <section class="ff-widget">
        <h4 class="ff-widget-title">
          <span class="ff-wicon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="m4 16 5-5 4 3 7-7" />
              <path d="M15 7h5v5" />
            </svg>
          </span>
          热门文章
        </h4>
        <ul v-if="hot.length" class="ff-hot-list">
          <li v-for="item in hot" :key="item.id" class="ff-hot-item">
            <router-link :to="`/post/${item.id}`">{{ item.title }}</router-link>
            <span class="ff-hot-date"> {{ formatDate(item.createdAt) }}</span>
          </li>
        </ul>
        <div v-else class="count">暂无</div>
      </section>

      <section class="ff-widget">
        <h4 class="ff-widget-title">
          <span class="ff-wicon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M5 20V10M10 20V4M15 20v-7M20 20V8" />
            </svg>
          </span>
          站点统计
        </h4>
        <div class="ff-stat-rows">
          <div class="ff-stat-row"><span>文章</span><strong>{{ stats ? formatCount(stats.posts) : '—' }}</strong></div>
          <div class="ff-stat-row"><span>阅读</span><strong>{{ stats ? formatCount(stats.totalViews) : '—' }}</strong></div>
          <div class="ff-stat-row"><span>评论</span><strong>{{ stats ? formatCount(stats.comments) : '—' }}</strong></div>
          <div class="ff-stat-row"><span>读者</span><strong>{{ stats ? formatCount(stats.users) : '—' }}</strong></div>
        </div>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.ff-announcement { padding: 12px 0 0; }
.ff-announcement-inner { display: flex; align-items: flex-start; gap: 12px; padding: 13px 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--accent-soft); }
.ff-announcement-mark { flex: 0 0 auto; display: grid; place-items: center; width: 24px; height: 24px; border-radius: 50%; background: var(--accent); color: #fff; font-weight: 800; font-size: 13px; }
.ff-announcement-copy { flex: 1; min-width: 0; display: grid; gap: 3px; }
.ff-announcement-copy strong { color: var(--accent-ink); font-size: 12px; letter-spacing: .04em; }
.ff-announcement-copy :deep(.markdown) { color: var(--ink-soft); font-size: 13px; line-height: 1.65; }
.ff-announcement-copy :deep(.markdown p) { margin: 0; display: inline; }
.ff-announcement-copy :deep(.markdown img) { max-height: 44px; vertical-align: middle; border-radius: 5px; }
.ff-announcement-link { flex: 0 0 auto; color: var(--accent-ink); font-size: 12px; font-weight: 700; white-space: nowrap; }
@media (max-width: 620px) { .ff-announcement-inner { flex-wrap: wrap; } .ff-announcement-link { margin-left: 36px; } }
</style>
