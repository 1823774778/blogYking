<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({ gfm: true, breaks: true })

const props = defineProps({
  content: { type: String, default: '' }
})

const html = computed(() => {
  const raw = marked.parse(props.content || '')
  return DOMPurify.sanitize(raw, { ADD_ATTR: ['target'] })
})

function handleMediaError(event) {
  const image = event.target
  if (!image || image.tagName !== 'IMG') return
  const alt = (image.getAttribute('alt') || '').trim()
  const fallback = document.createElement('span')
  fallback.className = 'md-image-fallback'
  fallback.textContent = alt ? `图片未能加载 · ${alt}` : '图片未能加载'
  image.replaceWith(fallback)
}
</script>

<template>
  <div class="markdown" v-html="html" @error.capture="handleMediaError"></div>
</template>
