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
</script>

<template>
  <div class="markdown" v-html="html"></div>
</template>
