<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { state, toast } from '../state'
import { formatCount } from '../utils'
import PostCard from '../components/PostCard.vue'
import Pagination from '../components/Pagination.vue'
import heroArt from '../assets/firefly-hero.webp'
import avatarArt from '../assets/avatar-firefly.webp'

const route = useRoute()
const router = useRouter()

const posts = ref([])
const total = ref(0)
const page = ref(1)
const size = 9
const keyword = ref('')
const activeTag = ref(route.query.tag ? String(route.query.tag) : '')
const sort = ref('latest')
const loading = ref(false)

const tags = ref([])
const hot = ref([])
const stats = ref(null)

const avatarSrc = () => state.site.avatar || avatarArt

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
  if (activeTag.value) {
    router.replace({ path: '/', query: { tag: activeTag.value } })
  }
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
    <img class="ff-hero-art" :src="heroArt" alt="" aria-hidden="true" />
    <div class="ff-hero-inner">
      <span class="ff-hero-eyebrow">{{ state.site.siteName }} · a personal blog</span>
      <h1>把想清楚的事，<br /><em>写下来。</em></h1>
      <p class="ff-hero-sub">{{ state.site.siteDesc }}</p>
      <div class="ff-hero-actions">
        <div class="ff-hero-search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-4-4" />
          </svg>
          <input v-model="keyword" type="search" placeholder="搜索文章、摘要或标签…" @keyup.enter="search" />
          <button type="button" aria-label="搜索" @click="search">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </button>
        </div>
        <div v-if="state.site.github || state.site.email" class="ff-hero-social">
          <a v-if="state.site.github" :href="state.site.github" target="_blank" rel="noopener" aria-label="GitHub">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 2a10 10 0 0 0-3.16 19.5c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.61.07-.61 1 .07 1.53 1.03 1.53 1.03.9 1.53 2.35 1.09 2.92.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.26-.45-1.27.1-2.64 0 0 .84-.27 2.75 1.02A9.56 9.56 0 0 1 12 6.8c.85 0 1.7.11 2.5.34 1.9-1.29 2.74-1.02 2.74-1.02.55 1.37.2 2.38.1 2.64.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.68-4.57 4.93.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2Z" />
            </svg>
          </a>
          <a v-if="state.site.email" :href="'mailto:' + state.site.email" aria-label="邮箱">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="m3 7 9 6 9-6" />
            </svg>
          </a>
        </div>
      </div>
    </div>
  </section>

  <div class="ff-home">
    <aside class="ff-sidebar">
      <div class="ff-profile-card">
        <div class="ff-avatar-ring">
          <img :src="avatarSrc()" :alt="state.site.siteName" />
        </div>
        <h2>{{ state.site.siteName }}</h2>
        <p class="ff-profile-desc">{{ state.site.siteDesc }}</p>
        <div v-if="state.site.github || state.site.email" class="ff-profile-social">
          <a v-if="state.site.github" :href="state.site.github" target="_blank" rel="noopener" aria-label="GitHub">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 2a10 10 0 0 0-3.16 19.5c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.61.07-.61 1 .07 1.53 1.03 1.53 1.03.9 1.53 2.35 1.09 2.92.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.26-.45-1.27.1-2.64 0 0 .84-.27 2.75 1.02A9.56 9.56 0 0 1 12 6.8c.85 0 1.7.11 2.5.34 1.9-1.29 2.74-1.02 2.74-1.02.55 1.37.2 2.38.1 2.64.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.68-4.57 4.93.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2Z" />
            </svg>
          </a>
          <a v-if="state.site.email" :href="'mailto:' + state.site.email" aria-label="邮箱">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="m3 7 9 6 9-6" />
            </svg>
          </a>
        </div>
        <div class="ff-profile-stats">
          <div>
            <strong>{{ stats ? formatCount(stats.posts) : '—' }}</strong>
            <small>文章</small>
          </div>
          <div>
            <strong>{{ stats ? formatCount(stats.totalViews) : '—' }}</strong>
            <small>阅读</small>
          </div>
          <div>
            <strong>{{ stats ? formatCount(stats.comments) : '—' }}</strong>
            <small>评论</small>
          </div>
        </div>
      </div>

      <div class="ff-widget">
        <h4 class="ff-widget-title">关于这里</h4>
        <p class="ff-announce">{{ state.site.about || state.site.siteDesc }}</p>
        <router-link class="ff-widget-link" to="/about">了解更多 →</router-link>
      </div>

      <div class="ff-widget">
        <h4 class="ff-widget-title">
          标签
          <span class="ff-widget-count">{{ tags.length }}</span>
        </h4>
        <div class="ff-tag-cloud">
          <button
            v-for="tag in tags.slice(0, 10)"
            :key="tag.name"
            class="tag"
            :class="{ active: activeTag === tag.name }"
            type="button"
            @click="pickTag(tag.name)"
          >
            {{ tag.name }} · {{ tag.count }}
          </button>
          <span v-if="!tags.length" class="count">暂无标签</span>
        </div>
      </div>
    </aside>

    <main class="ff-feed">
      <div class="ff-feed-head">
        <div class="ff-feed-tabs">
          <button type="button" :class="{ on: sort === 'latest' }" @click="setSort('latest')">最新</button>
          <button type="button" :class="{ on: sort === 'hot' }" @click="setSort('hot')">热门</button>
        </div>
        <div class="ff-feed-tags">
          <button
            v-for="tag in tags.slice(0, 3)"
            :key="tag.name"
            class="tag"
            :class="{ active: activeTag === tag.name }"
            type="button"
            @click="pickTag(tag.name)"
          >
            {{ tag.name }}
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

      <PostCard v-for="post in posts" :key="post.id" :post="post" />

      <Pagination :total="total" :page="page" :size="size" @change="changePage" />
    </main>

    <aside class="ff-widgets">
      <div class="ff-widget">
        <h4 class="ff-widget-title">热门文章</h4>
        <ul v-if="hot.length" class="ff-hot-list">
          <li v-for="(item, index) in hot" :key="item.id" class="ff-hot-item">
            <span class="ff-hot-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <router-link :to="`/post/${item.id}`">{{ item.title }}</router-link>
          </li>
        </ul>
        <div v-else class="count">暂无</div>
      </div>

      <div class="ff-widget">
        <h4 class="ff-widget-title">站点统计</h4>
        <div class="ff-stat-rows">
          <div class="ff-stat-row">
            <span>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M6 3h12v18l-6-4.2L6 21V3Z" /></svg>
              文章
            </span>
            <strong>{{ stats ? formatCount(stats.posts) : '—' }}</strong>
          </div>
          <div class="ff-stat-row">
            <span>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6Z" /><circle cx="12" cy="12" r="2.6" /></svg>
              阅读
            </span>
            <strong>{{ stats ? formatCount(stats.totalViews) : '—' }}</strong>
          </div>
          <div class="ff-stat-row">
            <span>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M21 12a8 8 0 0 1-8 8H6l-3 2V12a9 9 0 0 1 18 0Z" /></svg>
              评论
            </span>
            <strong>{{ stats ? formatCount(stats.comments) : '—' }}</strong>
          </div>
          <div class="ff-stat-row">
            <span>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="9" cy="8" r="3" /><path d="M3 20a6 6 0 0 1 12 0M16 5a3 3 0 0 1 0 6M17 20a5 5 0 0 0-3-4.5" /></svg>
              读者
            </span>
            <strong>{{ stats ? formatCount(stats.users) : '—' }}</strong>
          </div>
        </div>
      </div>

      <div class="ff-widget">
        <h4 class="ff-widget-title">站点信息</h4>
        <div class="ff-stat-rows">
          <div class="ff-stat-row"><span>构建平台</span><strong>Spring Boot</strong></div>
          <div class="ff-stat-row"><span>前端框架</span><strong>Vue 3</strong></div>
          <div class="ff-stat-row"><span>主题</span><strong>Firefly</strong></div>
        </div>
      </div>
    </aside>
  </div>
</template>
