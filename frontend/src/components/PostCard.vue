<script setup>
import { computed } from 'vue'
import { formatDate, formatCount, splitTags } from '../utils'
import coverDesk from '../assets/cover-desk.webp'
import coverKey from '../assets/cover-key.webp'
import coverNight from '../assets/cover-night.webp'

const props = defineProps({
  post: { type: Object, required: true }
})

const tags = computed(() => splitTags(props.post.tags))
const covers = [coverDesk, coverKey, coverNight]
const coverSrc = computed(() => {
  if (props.post.cover) return props.post.cover
  const tagText = tags.value.join(' ')
  if (/Java|Spring|安全|技术/.test(tagText)) return coverKey
  if (/生活|思考|夜|坚持/.test(tagText)) return coverNight
  if (/写作|随笔|效率|建站|博客/.test(tagText)) return coverDesk
  return covers[Number(props.post.id) % covers.length]
})
const category = computed(() => tags.value[0] || '随笔')
</script>

<template>
  <article class="ff-post-card" :class="{ 'no-cover': !coverSrc }">
    <div class="ff-post-body">
      <div class="ff-post-title-row">
        <h3>
          <router-link :to="`/post/${post.id}`">
            <span v-if="post.top" class="ff-top-flag">置顶</span>{{ post.title }}
          </router-link>
        </h3>
      </div>

      <div class="ff-post-meta">
        <span class="ff-meta-item">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <rect x="3" y="5" width="18" height="16" rx="2" />
            <path d="M16 3v4M8 3v4M3 11h18" />
          </svg>
          {{ formatDate(post.createdAt) }}
        </span>
        <span class="ff-meta-item cat">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" />
          </svg>
          {{ category }}
        </span>
        <span class="ff-meta-item">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6Z" />
            <circle cx="12" cy="12" r="2.6" />
          </svg>
          {{ formatCount(post.views) }}
        </span>
        <span class="ff-meta-item">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M21 12a8 8 0 0 1-8 8H6l-3 2V12a9 9 0 0 1 18 0Z" />
          </svg>
          {{ formatCount(post.comments) }}
        </span>
      </div>

      <p class="ff-post-summary">{{ post.summary }}</p>

      <div class="ff-post-footer">
        <div class="ff-post-tags">
          <router-link
            v-for="tag in tags.slice(0, 3)"
            :key="tag"
            class="tag"
            :to="`/?tag=${encodeURIComponent(tag)}`"
          >
            #{{ tag }}
          </router-link>
        </div>
        <router-link class="ff-read-more" :to="`/post/${post.id}`">
          阅读全文
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M5 12h14M13 6l6 6-6 6" />
          </svg>
        </router-link>
      </div>
    </div>

    <router-link v-if="coverSrc" class="ff-post-cover" :to="`/post/${post.id}`" tabindex="-1">
      <img :src="coverSrc" :alt="post.title" loading="lazy" />
    </router-link>
  </article>
</template>
