<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api'
import { state } from '../state'
import { formatDate, readingMinutes } from '../utils'
import MarkdownBody from '../components/MarkdownBody.vue'

const stats = ref(null)
const article = ref(null)
const articleContent = computed(() => article.value?.content || state.site.about)
const articleTitle = computed(() => article.value?.post?.title || '关于这里')
const articleSummary = computed(() => article.value?.post?.summary || '一个写字的地方。')

onMounted(async () => {
  const tasks = [
    http.get('/posts/stats').then((res) => { stats.value = res.data }).catch(() => {})
  ]
  tasks.push(http.get('/posts/slug/about').then((res) => { article.value = res.data }).catch(() => {}))
  await Promise.all(tasks)
})
</script>

<template>
  <div class="ff-about-page">
    <header class="ff-page-head">
      <span class="ff-hero-eyebrow">About · 关于本站</span>
      <h1>{{ articleTitle }}</h1>
      <p>{{ articleSummary }}</p>
      <div v-if="article?.post" class="ff-about-meta">
        <span>{{ article.post.authorName || '站长' }}</span>
        <span class="dot"></span>
        <span>{{ formatDate(article.post.createdAt) }}</span>
        <span class="dot"></span>
        <span>{{ readingMinutes(articleContent) }} 分钟读完</span>
      </div>
    </header>

    <div class="ff-about-grid">
      <div class="ff-about-card">
        <MarkdownBody :content="articleContent" />
        <div v-if="article?.post" class="ff-about-article-link">
          <router-link class="btn btn-ghost btn-sm" :to="`/post/${article.post.id}`">查看文章与评论</router-link>
          <span>这篇「关于本站」也是站内文章，可在后台文章管理中修改。</span>
        </div>
      </div>

      <aside class="ff-about-side">
        <div class="ff-widget">
          <h4 class="ff-widget-title">联系</h4>
          <div class="ff-contact-list">
            <a v-if="state.site.email" :href="'mailto:' + state.site.email">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <rect x="3" y="5" width="18" height="14" rx="2" />
                <path d="m3 7 9 6 9-6" />
              </svg>
              {{ state.site.email }}
            </a>
            <a v-if="state.site.github" :href="state.site.github" target="_blank" rel="noopener">
              <svg viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 2a10 10 0 0 0-3.16 19.5c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.61.07-.61 1 .07 1.53 1.03 1.53 1.03.9 1.53 2.35 1.09 2.92.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.26-.45-1.27.1-2.64 0 0 .84-.27 2.75 1.02A9.56 9.56 0 0 1 12 6.8c.85 0 1.7.11 2.5.34 1.9-1.29 2.74-1.02 2.74-1.02.55 1.37.2 2.38.1 2.64.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.68-4.57 4.93.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2Z" />
              </svg>
              GitHub
            </a>
            <span v-if="!state.site.email && !state.site.github" class="ff-contact-empty">暂无公开联系方式</span>
          </div>
        </div>

        <div v-if="stats" class="ff-widget">
          <h4 class="ff-widget-title">数字</h4>
          <div class="ff-stat-rows">
            <div class="ff-stat-row"><span>文章</span><strong>{{ stats.posts }}</strong></div>
            <div class="ff-stat-row"><span>阅读</span><strong>{{ stats.totalViews }}</strong></div>
            <div class="ff-stat-row"><span>评论</span><strong>{{ stats.comments }}</strong></div>
            <div class="ff-stat-row"><span>读者</span><strong>{{ stats.users }}</strong></div>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>
