<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { state, toast } from '../state'
import { formatDate, formatCount, initialOf, markdownHeadings, readingMinutes, splitTags } from '../utils'
import MarkdownBody from '../components/MarkdownBody.vue'
import CommentSection from '../components/CommentSection.vue'
import coverDesk from '../assets/cover-desk.webp'
import coverKey from '../assets/cover-key.webp'
import coverNight from '../assets/cover-night.webp'

const route = useRoute()
const router = useRouter()

const detail = ref(null)
const loading = ref(true)
const error = ref('')
const toggling = ref(false)

const tags = ref([])
const toc = computed(() => markdownHeadings(detail.value?.content))
const author = computed(() => detail.value?.post?.authorName || '站长')
const covers = [coverDesk, coverKey, coverNight]
const coverSrc = computed(() => {
  if (detail.value?.post?.cover) return detail.value.post.cover
  const tagText = tags.value.join(' ')
  if (/Java|Spring|安全|技术/.test(tagText)) return coverKey
  if (/生活|思考|夜|坚持/.test(tagText)) return coverNight
  if (/写作|随笔|效率|建站|博客/.test(tagText)) return coverDesk
  const id = Number(detail.value?.post?.id || 0)
  return covers[id % covers.length]
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await http.get(`/posts/${route.params.id}`)
    detail.value = res.data
    tags.value = splitTags(res.data.post.tags)
    document.title = `${res.data.post.title} · ${state.site.siteName}`
  } catch (e) {
    error.value = e.message
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function toggle(kind) {
  if (!state.token) {
    toast('登录后才能点赞或收藏')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  toggling.value = true
  try {
    const res = await http.post(`/posts/${detail.value.post.id}/${kind}`)
    if (kind === 'like') {
      detail.value.liked = res.data.active
      detail.value.post.likes = res.data.count
      toast(res.data.active ? '已喜欢，作者会收到通知' : '已取消喜欢')
    } else {
      detail.value.favorited = res.data.active
      detail.value.post.favorites = res.data.count
      toast(res.data.active ? '已收藏，作者会收到通知' : '已取消收藏')
    }
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    toggling.value = false
  }
}

watch(
  () => route.params.id,
  () => {
    if (route.params.id) load()
  },
  { immediate: true }
)
</script>

<template>
  <div v-if="loading" class="empty" style="padding: 120px 0">正在打开这篇文章…</div>
  <div v-else-if="error" class="empty" style="padding: 120px 0">
    这篇文章打不开：{{ error }}
    <div style="margin-top: 18px"><router-link class="btn btn-ghost btn-sm" to="/">返回首页</router-link></div>
  </div>

  <article v-else-if="detail" class="ff-article">
    <header class="ff-article-hero">
      <img class="ff-article-hero-art" :src="coverSrc" alt="" aria-hidden="true" />
      <div class="ff-article-hero-inner">
        <router-link to="/" class="ff-article-back">← 返回列表</router-link>
        <h1>{{ detail.post.title }}</h1>
        <div class="ff-article-hero-meta">
          <span>{{ formatDate(detail.post.createdAt) }}</span>
          <span class="dot"></span>
          <span>{{ detail.post.authorName }}</span>
          <span class="dot"></span>
          <span>{{ readingMinutes(detail.content) }} 分钟读完</span>
          <span class="dot"></span>
          <span>{{ formatCount(detail.post.views) }} 次阅读</span>
          <router-link
            v-for="tag in tags"
            :key="tag"
            class="tag"
            :to="`/?tag=${encodeURIComponent(tag)}`"
          >
            {{ tag }}
          </router-link>
        </div>
      </div>
    </header>

    <div class="ff-article-body">
      <div class="ff-article-main">
        <div class="ff-article-card">
          <div class="ff-article-byline">
            <span class="avatar sm">
              <img v-if="detail.post.authorAvatar" :src="detail.post.authorAvatar" alt="" />
              <template v-else>{{ initialOf(author) }}</template>
            </span>
            <div class="ff-article-byline-copy">
              <strong>{{ author }}</strong>
              <span>{{ formatDate(detail.post.createdAt) }} · {{ readingMinutes(detail.content) }} 分钟读完</span>
            </div>
            <div class="ff-article-actions">
              <button class="ff-action-btn" :class="{ on: detail.liked }" type="button" :disabled="toggling" @click="toggle('like')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                  <path d="M12 20.5s-8-4.9-8-10.2A4.6 4.6 0 0 1 12 7a4.6 4.6 0 0 1 8 3.3c0 5.3-8 10.2-8 10.2Z" />
                </svg>
                {{ detail.post.likes }}
              </button>
              <button class="ff-action-btn" :class="{ on: detail.favorited }" type="button" :disabled="toggling" @click="toggle('favorite')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                  <path d="M6 3h12v18l-6-4.2L6 21V3Z" />
                </svg>
                {{ detail.post.favorites }}
              </button>
            </div>
          </div>

          <MarkdownBody :content="detail.content" />
        </div>

        <div v-if="detail.post.commentSummary" class="ff-comment-summary">
          <div class="ff-comment-summary-head">
            <span class="ff-comment-summary-kicker">AI 评论总结</span>
            <span class="meta">{{ detail.post.commentSummaryAt ? formatDate(detail.post.commentSummaryAt) : '' }}</span>
          </div>
          <p>{{ detail.post.commentSummary }}</p>
        </div>

        <div class="ff-comments-card">
          <CommentSection :post-id="detail.post.id" />
        </div>
      </div>

      <aside class="ff-article-aside">
        <section v-if="toc.length" class="ff-widget ff-toc-card">
          <h4 class="ff-widget-title">
            <span class="ff-wicon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01" />
              </svg>
            </span>
            目录
          </h4>
          <nav class="ff-toc-list">
            <a
              v-for="item in toc"
              :key="item.id"
              :href="`#${item.id}`"
              :class="{ sub: item.level === 3 }"
            >
              {{ item.text }}
            </a>
          </nav>
        </section>

        <section class="ff-widget ff-article-info">
          <h4 class="ff-widget-title">
            <span class="ff-wicon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <circle cx="12" cy="8" r="3.5" />
                <path d="M5 21a7 7 0 0 1 14 0" />
              </svg>
            </span>
            关于作者
          </h4>
          <div class="ff-article-author">
            <span class="avatar">
              <img v-if="detail.post.authorAvatar" :src="detail.post.authorAvatar" alt="" />
              <template v-else>{{ initialOf(author) }}</template>
            </span>
            <div>
              <strong>{{ author }}</strong>
              <span>写于 {{ formatDate(detail.post.createdAt) }}</span>
            </div>
          </div>
          <div class="ff-article-numbers">
            <span>{{ formatCount(detail.post.views) }} 阅读</span>
            <span>{{ formatCount(detail.post.likes) }} 喜欢</span>
            <span>{{ formatCount(detail.post.comments) }} 评论</span>
          </div>
          <div v-if="tags.length" class="ff-article-tags">
            <router-link
              v-for="tag in tags"
              :key="tag"
              class="tag"
              :to="`/?tag=${encodeURIComponent(tag)}`"
            >
              #{{ tag }}
            </router-link>
          </div>
          <router-link class="ff-widget-link" to="/">← 回到文章列表</router-link>
        </section>
      </aside>
    </div>
  </article>
</template>
