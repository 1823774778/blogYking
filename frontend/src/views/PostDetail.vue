<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api'
import { state, toast } from '../state'
import { formatDate, formatCount, initialOf, markdownHeadings, readingMinutes, splitTags } from '../utils'
import MarkdownBody from '../components/MarkdownBody.vue'
import AiThinking from '../components/AiThinking.vue'
import CommentSection from '../components/CommentSection.vue'
import { coverForPost } from '../covers'

const route = useRoute()
const router = useRouter()

const detail = ref(null)
const loading = ref(true)
const error = ref('')
const toggling = ref(false)
const aiConfirmOpen = ref(false)
const aiLoading = ref(false)
const aiOverview = ref('')
const aiError = ref('')
const progress = ref(0)

const tags = ref([])
const toc = computed(() => markdownHeadings(detail.value?.content))
const author = computed(() => detail.value?.post?.authorName || '站长')
const commentsEnabled = computed(() => String(state.site.commentEnabled ?? 'true') !== 'false')
const coverSrc = computed(() => coverForPost(detail.value?.post, tags.value))

function updateProgress() {
  const root = document.documentElement
  const max = root.scrollHeight - root.clientHeight
  progress.value = max > 0 ? Math.min(1, root.scrollTop / max) : 0
}

onMounted(() => {
  updateProgress()
  window.addEventListener('scroll', updateProgress, { passive: true })
  window.addEventListener('resize', updateProgress)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', updateProgress)
  window.removeEventListener('resize', updateProgress)
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

function openAiOverview() {
  aiOverview.value = ''
  aiError.value = ''
  aiConfirmOpen.value = true
}

function closeAiOverview() {
  if (aiLoading.value) return
  aiConfirmOpen.value = false
}

async function generateOverview() {
  if (!detail.value?.post?.id) return
  aiLoading.value = true
  aiError.value = ''
  aiOverview.value = ''
  try {
    const res = await http.post(`/posts/${detail.value.post.id}/ai-overview`, null, { timeout: 90000 })
    aiOverview.value = String(res.data?.overview || '').trim()
    if (!aiOverview.value) aiError.value = 'AI 没有返回内容，请稍后再试'
  } catch (e) {
    aiError.value = e.message
  } finally {
    aiLoading.value = false
  }
}

async function copyOverview() {
  if (!aiOverview.value) return
  try {
    await navigator.clipboard.writeText(aiOverview.value)
    toast('总览已复制')
  } catch (e) {
    toast('复制失败，请手动选择文本', 'error')
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
  <div v-if="loading" class="ed-article-loading">
    <span>READING</span>
    正在打开这篇文章…
  </div>
  <div v-else-if="error" class="ed-article-error">
    <span>404 / UNAVAILABLE</span>
    <h1>这篇文章打不开</h1>
    <p>{{ error }}</p>
    <router-link class="ed-text-link" to="/">返回首页 →</router-link>
  </div>

  <article v-else-if="detail" class="ed-article">
    <div class="ed-progress" :style="{ transform: `scaleX(${progress})` }" aria-hidden="true"></div>

    <header class="ed-article-head">
      <div class="ed-article-head-inner">
        <div class="ed-article-topline">
          <router-link to="/" class="ed-back">← 返回目录 / INDEX</router-link>
          <span>{{ formatDate(detail.post.createdAt) }} · ISSUE {{ String(new Date().getMonth() + 1).padStart(2, '0') }}</span>
        </div>

        <div class="ed-article-kicker">
          <span>{{ tags[0] || '随笔' }}</span>
          <span>{{ readingMinutes(detail.content) }} MIN READ</span>
          <span v-if="detail.post.top">置顶 / FEATURED</span>
        </div>

        <h1>{{ detail.post.title }}</h1>
        <p v-if="detail.post.summary" class="ed-article-deck">{{ detail.post.summary }}</p>

        <div class="ed-article-byline">
          <span class="avatar ed-article-avatar">
            <img v-if="detail.post.authorAvatar" :src="detail.post.authorAvatar" alt="" />
            <template v-else>{{ initialOf(author) }}</template>
          </span>
          <div class="ed-article-byline-copy">
            <strong>{{ author }}</strong>
            <span>{{ formatDate(detail.post.createdAt) }} · {{ formatCount(detail.post.views) }} 次阅读</span>
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
      </div>
    </header>

    <figure class="ed-article-figure">
      <div class="ed-article-figure-inner">
        <img :src="coverSrc" :alt="detail.post.title" />
        <figcaption>
          <span>FIG. 01 / {{ tags[0] || '随笔' }}</span>
          <span>{{ formatDate(detail.post.createdAt) }} · {{ readingMinutes(detail.content) }} MIN</span>
        </figcaption>
      </div>
    </figure>

    <div class="ed-article-body">
      <aside class="ed-article-rail">
        <span class="ed-rail-label">CONTENTS / 目录</span>
        <nav v-if="toc.length" class="ed-toc-list">
          <a
            v-for="item in toc"
            :key="item.id"
            :href="`#${item.id}`"
            :class="{ sub: item.level === 3 }"
          >
            <span>{{ item.text }}</span>
          </a>
        </nav>
        <span v-else class="ed-rail-empty">本文没有分节标题</span>

        <div class="ed-rail-sep"></div>

        <div class="ed-rail-author">
          <span class="avatar ed-rail-avatar">
            <img v-if="detail.post.authorAvatar" :src="detail.post.authorAvatar" alt="" />
            <template v-else>{{ initialOf(author) }}</template>
          </span>
          <div>
            <strong>{{ author }}</strong>
            <span>写于 {{ formatDate(detail.post.createdAt) }}</span>
          </div>
        </div>

        <div class="ed-rail-numbers">
          <span><strong>{{ formatCount(detail.post.views) }}</strong> 阅读</span>
          <span><strong>{{ formatCount(detail.post.likes) }}</strong> 喜欢</span>
          <span><strong>{{ formatCount(detail.post.comments) }}</strong> 评论</span>
        </div>

        <div v-if="tags.length" class="ed-rail-tags">
          <router-link
            v-for="tag in tags"
            :key="tag"
            :to="`/?tag=${encodeURIComponent(tag)}`"
          >
            #{{ tag }}
          </router-link>
        </div>

        <router-link class="ed-rail-back" to="/">← 回到目录 / INDEX</router-link>
      </aside>

      <div class="ff-article-main">
        <div class="ed-article-content">
          <MarkdownBody :content="detail.content" />
        </div>

        <div class="ed-endmark">
          <span>END</span>
          <small>文章结束 · 谢谢阅读</small>
          <i aria-hidden="true"></i>
        </div>

        <div v-if="commentsEnabled && detail.post.commentSummary" class="ed-comment-summary">
          <div class="ed-comment-summary-head">
            <span>AI 评论总结 / DISCUSSION</span>
            <span>{{ detail.post.commentSummaryAt ? formatDate(detail.post.commentSummaryAt) : '' }}</span>
          </div>
          <p>{{ detail.post.commentSummary }}</p>
        </div>

        <div v-if="commentsEnabled" class="ed-comments">
          <CommentSection :post-id="detail.post.id" />
        </div>
      </div>
    </div>

    <button class="ff-ai-fab" type="button" aria-label="获取本文 AI 总览" @click="openAiOverview">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
        <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" />
        <circle cx="12" cy="12" r="3.2" />
      </svg>
      <span>AI 总览</span>
    </button>

    <div v-if="aiConfirmOpen" class="ff-ai-mask" @click.self="closeAiOverview">
      <section class="ff-ai-modal" role="dialog" aria-modal="true" aria-labelledby="ff-ai-title">
        <header class="ff-ai-head">
          <div>
            <span class="ff-ai-kicker">AI · 文章总览</span>
            <h3 id="ff-ai-title">{{ aiLoading ? 'AI 正在阅读这篇文章' : aiOverview ? '这篇文章讲了什么' : '要交给 AI 阅读吗？' }}</h3>
          </div>
          <button class="ff-ai-close" type="button" :disabled="aiLoading" aria-label="关闭" @click="closeAiOverview">×</button>
        </header>

        <AiThinking
          v-if="aiLoading"
          class="ff-ai-thinking"
          title="AI 正在通读全文"
          :steps="['读取文章标题与正文', '提炼一句话主旨', '整理三到五条核心要点', '判断适合谁阅读']"
          note="生成后会留在当前弹窗，不会提前关闭；通常需要 5–20 秒。"
        />

        <div v-else-if="aiOverview" class="ff-ai-overview">
          <div class="ff-ai-result-copy"><MarkdownBody :content="aiOverview" /></div>
          <p class="ff-ai-disclaimer">由 AI 根据本文正文生成，仅供参考。</p>
        </div>

        <div v-else class="ff-ai-confirm">
          <p>确认后，会把《{{ detail.post.title }}》的标题和正文发送给 AI，生成一句话主旨、3–5 条核心要点，以及适合谁阅读。</p>
          <ul>
            <li>只用于本次总览，不会改动文章内容</li>
            <li>同一篇文章 20 秒内只能请求一次</li>
          </ul>
          <div v-if="aiError" class="ff-ai-error">{{ aiError }}</div>
          <p class="meta">如果站点关闭了 AI 摘要功能，这里会提示暂不可用。</p>
        </div>

        <footer class="ff-ai-actions">
          <template v-if="aiLoading" />
          <template v-else-if="aiOverview">
            <button class="btn btn-ghost btn-sm" type="button" @click="copyOverview">复制</button>
            <button class="btn btn-ghost btn-sm" type="button" @click="openAiOverview">重新生成</button>
            <button class="btn btn-sm" type="button" @click="closeAiOverview">关闭</button>
          </template>
          <template v-else>
            <button class="btn btn-ghost btn-sm" type="button" @click="closeAiOverview">取消</button>
            <button class="btn btn-sm" type="button" @click="generateOverview">{{ aiError ? '再试一次' : '确认生成' }}</button>
          </template>
        </footer>
      </section>
    </div>
  </article>
</template>

<style scoped>
.ff-ai-fab {
  position: fixed;
  right: 28px;
  bottom: 30px;
  z-index: 50;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 44px;
  padding: 0 17px;
  border: 1px solid rgba(125, 211, 252, .35);
  border-radius: 999px;
  background: linear-gradient(135deg, #0b1220, #16233a);
  color: #e0f2fe;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: .02em;
  box-shadow: 0 10px 30px rgba(2, 8, 20, .28), inset 0 1px 0 rgba(255, 255, 255, .08);
  cursor: pointer;
  transition: transform .16s ease, box-shadow .16s ease, border-color .16s ease;
}
.ff-ai-fab svg { width: 17px; height: 17px; color: #7dd3fc; }
.ff-ai-fab:hover {
  transform: translateY(-2px);
  border-color: rgba(125, 211, 252, .7);
  box-shadow: 0 14px 34px rgba(2, 8, 20, .36);
}
.ff-ai-fab:active { transform: translateY(0); }
.ff-ai-mask {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: grid;
  place-items: center;
  padding: 22px;
  background: rgba(4, 10, 20, .6);
  backdrop-filter: blur(6px);
}
.ff-ai-modal {
  width: min(560px, 100%);
  max-height: min(76vh, 680px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 16px;
  background: var(--paper, #fff);
  color: var(--ink, #111827);
  box-shadow: 0 28px 80px rgba(2, 8, 20, .4);
}
.ff-ai-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px 16px;
  border-bottom: 1px solid var(--line, #e5e7eb);
}
.ff-ai-kicker { color: #2563eb; font-size: 10px; font-weight: 700; letter-spacing: .12em; }
.ff-ai-head h3 { margin: 6px 0 0; font-size: 20px; line-height: 1.3; }
.ff-ai-close {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted, #6b7280);
  font-size: 21px;
  cursor: pointer;
}
.ff-ai-close:hover { background: rgba(127, 127, 127, .1); }
.ff-ai-close:disabled { opacity: .45; cursor: not-allowed; }
.ff-ai-confirm, .ff-ai-overview { padding: 20px 24px; overflow-y: auto; }
.ff-ai-thinking { margin: 20px 24px; overflow-y: auto; }
.ff-ai-confirm p { margin: 0 0 12px; line-height: 1.75; }
.ff-ai-confirm ul { margin: 0 0 14px; padding-left: 18px; color: var(--muted, #6b7280); font-size: 13px; line-height: 1.8; }
.ff-ai-error {
  margin: 2px 0 12px;
  padding: 10px 12px;
  border: 1px solid rgba(239, 68, 68, .25);
  border-radius: 8px;
  background: rgba(239, 68, 68, .08);
  color: #dc2626;
  font-size: 13px;
  line-height: 1.6;
}
.ff-ai-result-copy { font-size: 14px; line-height: 1.85; }
.ff-ai-result-copy :deep(.markdown h1),
.ff-ai-result-copy :deep(.markdown h2),
.ff-ai-result-copy :deep(.markdown h3) { margin: 0 0 10px; font-size: 16px; }
.ff-ai-result-copy :deep(.markdown p) { margin: 0 0 10px; }
.ff-ai-result-copy :deep(.markdown li) { margin: 3px 0; }
.ff-ai-disclaimer { margin: 16px 0 0; color: var(--muted, #6b7280); font-size: 12px; }
.ff-ai-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 24px 18px;
  border-top: 1px solid var(--line, #e5e7eb);
}

/* Editorial skin for the AI overlay */
.ff-ai-fab {
  border-color: color-mix(in srgb, var(--ed-signal, #d33b1d) 44%, transparent);
  border-radius: 999px;
  background: var(--ed-ink, #17150f);
  color: var(--ed-paper-3, #fbf7ee);
  box-shadow: 0 16px 34px -18px rgba(20, 16, 10, .7);
}
.ff-ai-fab svg { color: var(--ed-signal, #d33b1d); }
.ff-ai-fab:hover {
  border-color: var(--ed-signal, #d33b1d);
  box-shadow: 0 18px 38px -16px rgba(20, 16, 10, .8);
}
.ff-ai-mask { background: rgba(12, 11, 8, .66); }
.ff-ai-modal {
  border: 1px solid var(--ed-line, #d4c8b4);
  border-top: 3px solid var(--ed-signal, #d33b1d);
  border-radius: 0;
  background: var(--ed-paper-3, #fbf7ee);
  color: var(--ed-ink, #17150f);
  box-shadow: 0 30px 70px -24px rgba(10, 8, 5, .6);
}
.ff-ai-head { border-bottom-color: var(--ed-line, #d4c8b4); }
.ff-ai-kicker { color: var(--ed-signal-deep, #a82a10); font-family: var(--ed-mono, monospace); letter-spacing: .14em; }
.ff-ai-head h3 { font-family: var(--ed-serif, serif); font-size: 25px; }
.ff-ai-close { border-radius: 2px; }
.ff-ai-confirm ul { color: var(--ed-muted, #746b5c); }
.ff-ai-disclaimer { color: var(--ed-muted, #746b5c); }
.ff-ai-actions { border-top-color: var(--ed-line, #d4c8b4); }
@media (max-width: 720px) {
  .ff-ai-fab { right: 16px; bottom: 18px; height: 42px; padding: 0 14px; }
  .ff-ai-mask { padding: 12px; align-items: end; }
  .ff-ai-modal { max-height: 84vh; border-radius: 16px 16px 0 0; }
  .ff-ai-head { padding: 18px 18px 14px; }
  .ff-ai-confirm, .ff-ai-overview { padding: 16px 18px; }
  .ff-ai-thinking { margin: 16px 18px; }
  .ff-ai-actions { padding: 12px 18px 16px; }
}
</style>

