<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({ gfm: true, breaks: true })

let headingIndex = 0
marked.use({
  renderer: {
    heading(text, level) {
      const id = `heading-${headingIndex}`
      headingIndex += 1
      return `<h${level} id="${id}">${text}</h${level}>\n`
    }
  }
})

const props = defineProps({
  content: { type: String, default: '' }
})

const ROOT_RELATIVE_DIRS = ['uploads/', 'images/', 'assets/', 'static/']

function normalizeImageSource(source) {
  let src = String(source || '').trim()
  if (!src) return ''
  if (src.startsWith('//')) src = `${window.location.protocol}${src}`
  if (src.startsWith('data:') || src.startsWith('blob:')) return src
  if (src.startsWith('/')) return src

  const relative = src.replace(/^\.\//, '')
  if (ROOT_RELATIVE_DIRS.some((dir) => relative.startsWith(dir))) return `/${relative}`

  const origin = window.location.origin
  try {
    const url = new URL(src, window.location.href)
    const path = `${url.pathname}${url.search}`
    const isLocalHost = ['localhost', '127.0.0.1', '0.0.0.0'].includes(url.hostname)
    if (isLocalHost && window.location.hostname !== url.hostname) return `${origin}${path}${url.hash}`
    if (isLocalHost && window.location.port !== url.port) return `${origin}${path}${url.hash}`
    if (url.origin === origin) return path
    return url.href
  } catch {
    /* 交给下面的相对路径处理 */
  }

  if (src.startsWith('/')) return src
  const normalized = src.replace(/^\.\//, '')
  if (ROOT_RELATIVE_DIRS.some((dir) => normalized.startsWith(dir))) return `/${normalized}`
  return src
}

function enhanceMarkdown(rawHtml) {
  const template = document.createElement('template')
  template.innerHTML = rawHtml
  const fragment = template.content

  fragment.querySelectorAll('img').forEach((image) => {
    const src = normalizeImageSource(image.getAttribute('src'))
    if (src) image.setAttribute('src', src)
    image.setAttribute('referrerpolicy', 'no-referrer')
    image.setAttribute('loading', 'lazy')
    image.setAttribute('decoding', 'async')
    image.setAttribute('draggable', 'false')
    image.removeAttribute('srcset')
    image.removeAttribute('sizes')
    image.classList.add('md-image')
  })

  fragment.querySelectorAll('a[href]').forEach((anchor) => {
    const href = anchor.getAttribute('href') || ''
    if (/^https?:\/\//i.test(href) && !href.startsWith(window.location.origin)) {
      anchor.setAttribute('target', '_blank')
      anchor.setAttribute('rel', 'noopener noreferrer')
    }
  })

  return template.innerHTML
}

const html = computed(() => {
  headingIndex = 0
  const raw = marked.parse(props.content || '')
  const sanitized = DOMPurify.sanitize(raw, {
    ADD_ATTR: ['target', 'loading', 'decoding', 'referrerpolicy']
  })
  return enhanceMarkdown(sanitized)
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
