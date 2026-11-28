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
  <article class="ed-about-page">
    <header class="ed-about-head">
      <div class="ed-about-topline">
        <span>ABOUT / 关于本站</span>
        <span>{{ state.site.siteName }} · EST. 2026</span>
      </div>
      <div class="ed-about-head-grid">
        <div>
          <span class="ed-eyebrow">A NOTE FROM THE EDITOR</span>
          <h1>{{ articleTitle }}</h1>
        </div>
        <p class="ed-about-deck">{{ articleSummary }}</p>
      </div>
      <div class="ed-about-meta">
        <span>{{ article?.post?.authorName || '站长' }}</span>
        <span v-if="article?.post">{{ formatDate(article.post.createdAt) }}</span>
        <span>{{ readingMinutes(articleContent) }} 分钟读完</span>
        <span>{{ stats ? stats.posts : '—' }} 篇文章</span>
      </div>
    </header>

    <div class="ed-about-body">
      <aside class="ed-about-rail">
        <span class="ed-rail-label">COLOPHONE</span>
        <p class="ed-about-rail-note">这个站点由一个人维护，写作、排版、开发与校对都在同一张桌子上完成。</p>

        <div class="ed-about-contact">
          <span class="ed-ledger-label">联系 / CONTACT</span>
          <a v-if="state.site.email" :href="'mailto:' + state.site.email">
            <span>邮箱</span>
            <em>{{ state.site.email }}</em>
          </a>
          <a v-if="state.site.github" :href="state.site.github" target="_blank" rel="noopener">
            <span>GitHub</span>
            <em>查看源码 ↗</em>
          </a>
          <span v-if="!state.site.email && !state.site.github" class="ed-rail-empty">暂无公开联系方式</span>
        </div>

        <div v-if="stats" class="ed-about-numbers">
          <span class="ed-ledger-label">数字 / BY THE NUMBERS</span>
          <div><strong>{{ stats.posts }}</strong><span>篇文章</span></div>
          <div><strong>{{ stats.totalViews }}</strong><span>次阅读</span></div>
          <div><strong>{{ stats.comments }}</strong><span>条评论</span></div>
          <div><strong>{{ stats.users }}</strong><span>位读者</span></div>
        </div>
      </aside>

      <div class="ed-about-content">
        <div class="ed-about-reading">
          <MarkdownBody :content="articleContent" />
        </div>
        <div class="ed-endmark">
          <span>COLOPHONE</span>
          <small>写到这里，继续往前。</small>
          <i aria-hidden="true"></i>
        </div>
        <div v-if="article?.post" class="ed-about-link">
          <router-link class="ed-text-link" :to="`/post/${article.post.id}`">查看文章与评论 →</router-link>
          <span>这篇「关于本站」也是站内文章，可在后台文章管理中修改。</span>
        </div>
      </div>
    </div>
  </article>
</template>
