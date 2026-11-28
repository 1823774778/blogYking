<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { state, toast } from '../state'
import { formatDate, formatCount, plainExcerpt, splitTags } from '../utils'
import PostCard from '../components/PostCard.vue'
import Pagination from '../components/Pagination.vue'
import MarkdownBody from '../components/MarkdownBody.vue'
import { coverForPost, heroCoverForPost } from '../covers'

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

const year = new Date().getFullYear()
const issue = String(new Date().getMonth() + 1).padStart(2, '0')
const isFiltered = computed(() => Boolean(activeTag.value || keyword.value.trim()))
const heroPost = computed(() => {
  if (page.value !== 1 || isFiltered.value) return null
  return posts.value.find((post) => {
    if (String(post.slug || '') === 'about') return false
    return !String(post.tags || '').includes('关于本站')
  }) || posts.value[0] || null
})
const aboutPost = computed(() => posts.value.find((post) => (
  String(post.slug || '') === 'about' || String(post.tags || '').includes('关于本站')
)) || null)
const feedPosts = computed(() => posts.value.filter((post) => {
  if (post.id === heroPost.value?.id) return false
  if (post.id === aboutPost.value?.id) return false
  return true
}))
const leadPost = computed(() => feedPosts.value[0] || null)
const sidePosts = computed(() => feedPosts.value.slice(1, 3))
const archivePosts = computed(() => {
  const rest = feedPosts.value.slice(3)
  if (aboutPost.value && !rest.some((post) => post.id === aboutPost.value.id)) {
    rest.push(aboutPost.value)
  }
  return rest
})
const pageTitle = computed(() => {
  if (keyword.value.trim()) return '检索'
  if (activeTag.value) return '标签'
  if (page.value > 1) return '存档'
  return '文章存档'
})
const pageKicker = computed(() => {
  if (keyword.value.trim()) return `SEARCH / ${keyword.value.trim()}`
  if (activeTag.value) return `TAG / ${activeTag.value}`
  return `ARCHIVE / PAGE ${String(page.value).padStart(2, '0')}`
})
const announcementVisible = computed(() =>
  String(state.site.announcementEnabled).toLowerCase() === 'true' && String(state.site.announcement || '').trim()
)
const heroTag = computed(() => (heroPost.value ? splitTags(heroPost.value.tags)[0] || '随笔' : '随笔'))
const aboutIntro = computed(() => {
  const raw = String(state.site.about || state.site.siteDesc || aboutPost.value?.summary || '')
  const opening = raw.split(/\n\s*#{3,6}\s+/)[0] || raw
  return plainExcerpt(opening, 320)
})

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
    /* 索引区加载失败不影响主列表 */
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

function scrollToStories() {
  document.getElementById('ed-stories')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
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
  <section v-if="heroPost" class="ed-hero">
    <img class="ed-hero-media" :src="heroCoverForPost(heroPost)" :alt="heroPost.title" />
    <div class="ed-hero-scrim" aria-hidden="true"></div>
    <div class="ed-hero-frame">
      <div class="ed-hero-topline">
        <span>VOL. 01 — {{ year }}</span>
        <span>{{ state.site.siteName }} · 独立写作与生活观察</span>
        <span>ISSUE {{ issue }} / 封面文章</span>
      </div>

      <div class="ed-hero-copy">
        <div class="ed-hero-kicker">
          <span class="ed-signal-line"></span>
          封面文章 / COVER STORY
        </div>
        <h1><router-link :to="`/post/${heroPost.id}`">{{ heroPost.title }}</router-link></h1>
        <p class="ed-hero-summary">{{ heroPost.summary }}</p>
        <div class="ed-hero-actions">
          <router-link class="ed-cta" :to="`/post/${heroPost.id}`">
            阅读这篇文章
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M4 12h15M13 6l6 6-6 6" />
            </svg>
          </router-link>
          <span class="ed-hero-meta">{{ formatDate(heroPost.createdAt) }} · {{ heroTag }}</span>
        </div>
      </div>

      <div class="ed-hero-side">
        <div class="ed-hero-vertical">{{ state.site.heroSubtitle || '用文字抵抗遗忘' }}</div>
        <span class="ed-seal">{{ (state.site.siteName || '墨').slice(0, 1) }}</span>
      </div>

      <div class="ed-hero-card">
        <span class="ed-hero-card-label">THIS ISSUE</span>
        <div class="ed-hero-card-main">NO. {{ issue }}</div>
        <div class="ed-hero-card-row"><span>文章</span><strong>{{ total }}</strong></div>
        <div class="ed-hero-card-row"><span>状态</span><strong>持续更新</strong></div>
      </div>

      <button class="ed-hero-scroll" type="button" @click="scrollToStories">
        <span>继续阅读 / CONTENTS</span>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="M12 4v15M6 13l6 6 6-6" />
        </svg>
      </button>
    </div>
  </section>

  <section v-else class="ed-pagehead">
    <div class="ed-pagehead-frame">
      <div class="ed-pagehead-top">
        <span>{{ pageKicker }}</span>
        <span>{{ state.site.siteName }} / {{ year }}</span>
      </div>
      <div class="ed-pagehead-body">
        <div>
          <span class="ed-eyebrow">THE ARCHIVE</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="ed-pagehead-side">
          <p v-if="keyword.trim()">正在检索「{{ keyword.trim() }}」的相关文章。</p>
          <p v-else-if="activeTag">正在浏览标签「{{ activeTag }}」下的全部文章。</p>
          <p v-else>所有已发布文字都在这里，按时间或热度重新翻阅。</p>
          <div class="ed-page-search">
            <input v-model="keyword" type="search" placeholder="搜索文章、摘要或标签…" @keyup.enter="search" />
            <button type="button" @click="search">检索</button>
          </div>
        </div>
      </div>
      <div v-if="isFiltered" class="ed-pagehead-note">
        <span>SEARCH RESULT</span>
        <button type="button" @click="clearFilters">清除筛选 / CLEAR</button>
      </div>
    </div>
  </section>

  <section v-if="announcementVisible" class="ed-notice">
    <div class="ed-notice-mark">公告<br /><small>NOTICE</small></div>
    <div class="ed-notice-copy">
      <strong>{{ state.site.announcementTitle || '站点公告' }}</strong>
      <MarkdownBody :content="state.site.announcement" />
    </div>
    <a v-if="state.site.announcementLink" class="ed-notice-link" :href="state.site.announcementLink">查看详情 →</a>
  </section>

  <div class="ed-toolbar">
    <div class="ed-toolbar-left">
      <span class="ed-toolbar-label">浏览 / BROWSE</span>
      <div class="ed-sort">
        <button type="button" :class="{ on: sort === 'latest' }" @click="setSort('latest')">最新</button>
        <button type="button" :class="{ on: sort === 'hot' }" @click="setSort('hot')">热门</button>
      </div>
    </div>
    <div class="ed-toolbar-tags">
      <button
        v-for="tag in tags.slice(0, 5)"
        :key="tag.name"
        type="button"
        :class="{ on: activeTag === tag.name }"
        @click="pickTag(tag.name)"
      >
        #{{ tag.name }}
      </button>
    </div>
    <div class="ed-toolbar-count">{{ total }} 篇文章</div>
  </div>

  <div v-if="loading" class="ed-loading">
    <span>INDEXING</span>
    正在翻页…
  </div>

  <div v-else-if="!posts.length" class="ed-empty">
    <span class="ed-empty-no">00</span>
    <div>
      <h2>这里还没有文章</h2>
      <p>换一个关键词，或者从标签里重新找起。空白的页面也值得被保留。</p>
      <button type="button" class="ed-text-link" @click="clearFilters">回到首页 / BACK HOME →</button>
    </div>
  </div>

  <template v-else>
    <section v-if="leadPost" id="ed-stories" class="ed-section">
      <div class="ed-section-head">
        <div class="ed-section-no">01</div>
        <div class="ed-section-title">
          <span>SELECTED WRITINGS</span>
          <h2>编辑精选</h2>
        </div>
        <div class="ed-section-line"></div>
        <div class="ed-section-note">
          {{ posts.length }} 篇正在展出
          <span>UPDATED {{ formatDate(leadPost.createdAt) }}</span>
        </div>
      </div>

      <div class="ed-lead-grid" :class="{ 'is-single': !sidePosts.length }">
        <PostCard :post="leadPost" variant="lead" index="01" />
        <div v-if="sidePosts.length" class="ed-lead-side">
          <PostCard
            v-for="(post, index) in sidePosts"
            :key="post.id"
            :post="post"
            variant="stack"
            :index="String(index + 2).padStart(2, '0')"
          />
        </div>
      </div>
    </section>

    <section v-if="archivePosts.length" class="ed-archive">
      <div class="ed-archive-head">
        <div class="ed-section-title">
          <span>CONTINUE READING</span>
          <h2>继续翻阅</h2>
        </div>
        <span class="ed-archive-count">02 / {{ String(archivePosts.length).padStart(2, '0') }}</span>
      </div>
      <div class="ed-archive-list">
        <PostCard
          v-for="(post, index) in archivePosts"
          :key="post.id"
          :post="post"
          variant="row"
          :index="String(index + 4).padStart(2, '0')"
        />
      </div>
    </section>

    <div v-if="total > size" class="ed-pagination">
      <Pagination :total="total" :page="page" :size="size" @change="changePage" />
    </div>

    <section class="ed-ledger">
      <div class="ed-ledger-head">
        <div>
          <span class="ed-eyebrow">INDEX &amp; COLOPHONE</span>
          <h2>索引与留白</h2>
        </div>
        <p>把文章之外的东西也摆出来：关于这个站点、散落的标签、被读得最多的几篇，以及一些不必每天更新但值得被记住的数字。</p>
      </div>

      <div class="ed-ledger-grid">
        <div class="ed-ledger-about">
          <span class="ed-ledger-label">关于 / ABOUT</span>
          <h3>{{ state.site.siteName }}</h3>
          <p>{{ aboutIntro }}</p>
          <router-link to="/about">阅读关于本站 →</router-link>
        </div>

        <div class="ed-ledger-tags">
          <span class="ed-ledger-label">标签 / TAGS</span>
          <div class="ed-ledger-tag-list">
            <button
              v-for="tag in tags.slice(0, 8)"
              :key="tag.name"
              type="button"
              @click="pickTag(tag.name)"
            >
              <span>#{{ tag.name }}</span>
              <em>{{ String(tag.count).padStart(2, '0') }}</em>
            </button>
            <span v-if="!tags.length" class="ed-ledger-empty">暂无标签</span>
          </div>
        </div>

        <div class="ed-ledger-hot">
          <span class="ed-ledger-label">热门 / MOST READ</span>
          <ol v-if="hot.length">
            <li v-for="(item, index) in hot" :key="item.id">
              <span>{{ String(index + 1).padStart(2, '0') }}</span>
              <router-link :to="`/post/${item.id}`">{{ item.title }}</router-link>
              <time>{{ formatDate(item.createdAt) }}</time>
            </li>
          </ol>
          <span v-else class="ed-ledger-empty">暂无阅读数据</span>
        </div>
      </div>

      <div class="ed-stats">
        <div><strong>{{ stats ? formatCount(stats.posts) : '—' }}</strong><span>篇文章 / POSTS</span></div>
        <div><strong>{{ stats ? formatCount(stats.totalViews) : '—' }}</strong><span>阅读 / VIEWS</span></div>
        <div><strong>{{ stats ? formatCount(stats.comments) : '—' }}</strong><span>评论 / COMMENTS</span></div>
        <div><strong>{{ stats ? formatCount(stats.users) : '—' }}</strong><span>读者 / READERS</span></div>
      </div>
    </section>
  </template>
</template>
