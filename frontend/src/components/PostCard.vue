<script setup>
import { computed } from 'vue'
import { formatDate, formatCount, initialOf, splitTags } from '../utils'
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
const author = computed(() => props.post.authorName || '站长')
const visibleTags = computed(() => tags.value.slice(1, 4))
</script>

<template>
  <article class="ff-post-card" :class="{ 'no-cover': !coverSrc }">
    <router-link v-if="coverSrc" class="ff-post-cover" :to="`/post/${post.id}`" tabindex="-1">
      <img :src="coverSrc" :alt="post.title" loading="lazy" />
      <span v-if="post.top" class="ff-top-flag">置顶</span>
    </router-link>

    <div class="ff-post-body">
      <div class="ff-post-topic">
        <span class="ff-post-cat">{{ category }}</span>
        <router-link
          v-for="tag in visibleTags"
          :key="tag"
          class="ff-post-tag"
          :to="`/?tag=${encodeURIComponent(tag)}`"
        >
          #{{ tag }}
        </router-link>
      </div>

      <h3>
        <router-link :to="`/post/${post.id}`">{{ post.title }}</router-link>
      </h3>

      <p class="ff-post-summary">{{ post.summary }}</p>

      <div class="ff-post-byline">
        <span class="ff-post-avatar">{{ initialOf(author) }}</span>
        <span class="ff-post-author">{{ author }}</span>
        <span class="ff-post-date">发布于 {{ formatDate(post.createdAt) }}</span>
        <span class="ff-post-views">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6Z" />
            <circle cx="12" cy="12" r="2.6" />
          </svg>
          {{ formatCount(post.views) }}
        </span>
      </div>
    </div>
  </article>
</template>
