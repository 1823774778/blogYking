<script setup>
import { computed } from 'vue'
import { formatDate, formatCount, splitTags } from '../utils'
import { coverForPost } from '../covers'

const props = defineProps({
  post: { type: Object, required: true },
  variant: { type: String, default: 'stack' },
  index: { type: String, default: '01' }
})

const tags = computed(() => splitTags(props.post.tags))
const coverSrc = computed(() => coverForPost(props.post, tags.value))
const category = computed(() => tags.value[0] || '随笔')
const author = computed(() => props.post.authorName || '站长')
const visibleTags = computed(() => tags.value.slice(1, 3))
const isRow = computed(() => props.variant === 'row')
</script>

<template>
  <article v-if="isRow" class="ed-row">
    <span class="ed-row-no">{{ index }}</span>
    <div class="ed-row-body">
      <div class="ed-row-kicker">
        <span>{{ category }}</span>
        <span>{{ formatDate(post.createdAt) }}</span>
      </div>
      <h3><router-link :to="`/post/${post.id}`">{{ post.title }}</router-link></h3>
      <p>{{ post.summary }}</p>
    </div>
    <div class="ed-row-meta">
      <span>{{ author }}</span>
      <span>{{ formatCount(post.views) }} 阅读</span>
      <router-link :to="`/post/${post.id}`" aria-label="阅读全文">READ →</router-link>
    </div>
    <router-link class="ed-row-cover" :to="`/post/${post.id}`" tabindex="-1" aria-hidden="true">
      <img :src="coverSrc" :alt="post.title" loading="lazy" />
    </router-link>
  </article>

  <article v-else class="ed-story" :class="`ed-story--${variant}`" :data-index="index">
    <router-link class="ed-story-cover" :to="`/post/${post.id}`" tabindex="-1">
      <img :src="coverSrc" :alt="post.title" loading="lazy" />
      <span class="ed-story-no">{{ index }}</span>
      <span v-if="post.top" class="ed-top-flag">置顶</span>
    </router-link>

    <div class="ed-story-body" :data-index="index">
      <div class="ed-story-kicker">
        <span>{{ category }}</span>
        <span>{{ formatDate(post.createdAt) }}</span>
      </div>
      <h3><router-link :to="`/post/${post.id}`">{{ post.title }}</router-link></h3>
      <p class="ed-story-summary">{{ post.summary }}</p>
      <div v-if="visibleTags.length && variant === 'lead'" class="ed-story-tags">
        <router-link
          v-for="tag in visibleTags"
          :key="tag"
          :to="`/?tag=${encodeURIComponent(tag)}`"
        >
          #{{ tag }}
        </router-link>
      </div>
      <div class="ed-story-foot">
        <span>{{ author }}</span>
        <span class="ed-sep">·</span>
        <span>{{ formatCount(post.views) }} 阅读</span>
        <router-link class="ed-story-arrow" :to="`/post/${post.id}`" :aria-label="`阅读 ${post.title}`">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M4 12h15M13 6l6 6-6 6" />
          </svg>
        </router-link>
      </div>
    </div>
  </article>
</template>
