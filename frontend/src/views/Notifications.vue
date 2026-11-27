<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api'
import { isNoticeNew, markNoticeRead, markNoticesSeen, noticeChangeEvent, seenNoticeId } from '../notices'
import { formatDate, formatDateTime } from '../utils'

const route = useRoute()
const PAGE_SIZE = 8
const TYPE_META = {
  INFO: { label: '站务公告', short: '公告', tone: 'info' },
  SYSTEM: { label: '系统通知', short: '系统', tone: 'system' },
  UPDATE: { label: '版本更新', short: '更新', tone: 'update' }
}
const TYPE_TABS = [
  { key: 'all', label: '全部通知', short: '全部', countKey: 'all' },
  { key: 'info', label: '站务公告', short: '公告', countKey: 'info' },
  { key: 'system', label: '系统通知', short: '系统', countKey: 'system' },
  { key: 'update', label: '版本更新', short: '更新', countKey: 'update' }
]

const initialType = String(route.query.type || '').toLowerCase()
const type = ref(['info', 'system', 'update'].includes(initialType) ? initialType : 'all')
const keyword = ref('')
const page = ref(1)
const items = ref([])
const total = ref(0)
const loading = ref(true)
const error = ref('')
const meta = reactive({
  hasMore: false,
  latestId: 0,
  latestAt: '',
  pinnedCount: 0,
  typeCounts: { all: 0, info: 0, system: 0, update: 0 }
})
const seen = ref(seenNoticeId())
const noticeRevision = ref(0)
const selected = ref(null)
const detailOpen = ref(false)
const closeButton = ref(null)
let searchTimer = null
let requestSeq = 0

const tabs = computed(() => TYPE_TABS.map((item) => ({
  ...item,
  count: Number(meta.typeCounts?.[item.countKey] || 0)
})))
const allCount = computed(() => Number(meta.typeCounts?.all || 0))
const latestAtLabel = computed(() => meta.latestAt ? formatDate(meta.latestAt) : '暂无')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const currentFilterLabel = computed(() => tabs.value.find((item) => item.key === type.value)?.label || '全部通知')
const newOnPage = computed(() => {
  noticeRevision.value
  return items.value.filter((item) => isNoticeNew(item)).length
})
const canMarkAll = computed(() => {
  noticeRevision.value
  return Number(meta.latestId) > seen.value
})

function typeInfo(value) {
  return TYPE_META[String(value || '').toUpperCase()] || TYPE_META.INFO
}

function previewOf(value) {
  const text = String(value || '').replace(/\s+/g, ' ').trim()
  return text.length > 122 ? `${text.slice(0, 122)}…` : text
}

function dateParts(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return { day: '--', month: '通知' }
  return {
    day: String(date.getDate()).padStart(2, '0'),
    month: `${date.getMonth() + 1} 月`
  }
}

function isNew(item) {
  noticeRevision.value
  return isNoticeNew(item)
}

async function loadNotifications() {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const res = await http.get('/notifications/history', {
      params: {
        page: page.value,
        size: PAGE_SIZE,
        type: type.value,
        keyword: keyword.value.trim()
      }
    })
    if (seq !== requestSeq) return
    const data = res.data || {}
    items.value = data.items || []
    total.value = Number(data.total || 0)
    page.value = Number(data.page || 1)
    if (res.data && typeof res.data.hasMore === 'boolean') meta.hasMore = res.data.hasMore
    meta.latestId = Number(data.latestId || 0)
    meta.latestAt = data.latestAt || ''
    meta.pinnedCount = Number(data.pinnedCount || 0)
    meta.typeCounts = {
      all: Number(data.typeCounts?.all || 0),
      info: Number(data.typeCounts?.info || 0),
      system: Number(data.typeCounts?.system || 0),
      update: Number(data.typeCounts?.update || 0)
    }
  } catch (e) {
    if (seq !== requestSeq) return
    items.value = []
    total.value = 0
    error.value = e?.message || '通知暂时无法读取'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function setType(nextType) {
  if (type.value === nextType) return
  type.value = nextType
  page.value = 1
  loadNotifications()
}

function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    loadNotifications()
  }, 280)
}

function clearSearch() {
  clearTimeout(searchTimer)
  keyword.value = ''
  page.value = 1
  loadNotifications()
}

function clearFilters() {
  clearTimeout(searchTimer)
  keyword.value = ''
  type.value = 'all'
  page.value = 1
  loadNotifications()
}

function goPage(nextPage) {
  const target = Math.min(totalPages.value, Math.max(1, nextPage))
  if (target === page.value) return
  page.value = target
  loadNotifications()
  if (typeof window !== 'undefined') {
    const feedHead = document.querySelector('.nt-feed-head')
    if (feedHead) {
      window.scrollTo({ top: Math.max(0, feedHead.getBoundingClientRect().top + window.scrollY - 84), behavior: 'smooth' })
    }
  }
}

function openDetail(item) {
  if (!item) return
  selected.value = item
  detailOpen.value = true
  markNoticeRead(item)
  noticeRevision.value += 1
}

function closeDetail() {
  detailOpen.value = false
  selected.value = null
}

function markAllRead() {
  if (!meta.latestId) return
  markNoticesSeen(meta.latestId)
  seen.value = Number(meta.latestId)
  noticeRevision.value += 1
}

function onKeydown(event) {
  if (event.key === 'Escape' && detailOpen.value) {
    closeDetail()
  }
}

function onBodyScrollLock(open) {
  if (typeof document === 'undefined') return
  document.body.style.overflow = open ? 'hidden' : ''
}

watch(detailOpen, async (open) => {
  onBodyScrollLock(open)
  if (open) {
    await nextTick()
    closeButton.value?.focus()
  }
})

function syncSeenState() {
  seen.value = seenNoticeId()
  noticeRevision.value += 1
}

onMounted(() => {
  loadNotifications()
  document.addEventListener('keydown', onKeydown)
  window.addEventListener(noticeChangeEvent, syncSeenState)
})

onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener(noticeChangeEvent, syncSeenState)
  document.body.style.overflow = ''
})

</script>

<template>
  <div class="nt-page">
    <header class="nt-hero">
      <div class="nt-hero-copy">
        <div class="nt-eyebrow">
          <span class="nt-signal-dot"></span>
          SITE SIGNAL <i>/</i> 通知中心
        </div>
        <h1>通知中心</h1>
        <p class="nt-lead">版本更新、系统维护与站务公告，都安静地归档在这里。<br class="nt-lead-break" />不用追着时间线跑，重要的事会留在这里。</p>
        <div class="nt-hero-meta">
          <span>已发布 <b>{{ allCount }}</b> 条</span>
          <span class="nt-meta-dot"></span>
          <span>最近更新 {{ latestAtLabel }}</span>
        </div>
      </div>

      <aside class="nt-signal-card" aria-label="通知概览">
        <div class="nt-signal-head">
          <span>INBOX INDEX</span>
          <small>通知索引</small>
        </div>
        <div class="nt-signal-count">
          <strong>{{ allCount }}</strong>
          <span>条通知<br />已归档</span>
        </div>
        <div class="nt-signal-rows">
          <div>
            <span>置顶</span>
            <b>{{ meta.pinnedCount }}</b>
          </div>
          <div>
            <span>本页</span>
            <b>{{ items.length }}</b>
          </div>
        </div>
        <button class="nt-read-all" type="button" :disabled="!canMarkAll" @click="markAllRead">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="m5 12 4.2 4.2L19 6.5" />
          </svg>
          全部标为已读
        </button>
      </aside>
    </header>

    <section class="nt-toolbar" aria-label="通知筛选">
      <div class="nt-tabs" role="tablist">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="nt-tab"
          :class="{ active: type === tab.key }"
          type="button"
          role="tab"
          :aria-selected="type === tab.key ? 'true' : 'false'"
          @click="setType(tab.key)"
        >
          <span>{{ tab.short }}</span>
          <em>{{ tab.count }}</em>
        </button>
      </div>
      <div class="nt-search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-4-4" />
        </svg>
        <input
          v-model="keyword"
          type="search"
          placeholder="搜索标题或内容…"
          aria-label="搜索通知"
          @input="onSearchInput"
        />
        <button v-if="keyword" class="nt-search-clear" type="button" aria-label="清空搜索" @click="clearSearch">×</button>
      </div>
    </section>

    <section class="nt-feed-head">
      <div>
        <span class="nt-feed-kicker">ARCHIVE <i>/</i> {{ type === 'all' ? 'ALL SIGNALS' : 'FILTERED' }}</span>
        <h2>{{ currentFilterLabel }}</h2>
      </div>
      <div class="nt-feed-stats">
        <span v-if="newOnPage" class="nt-new-note">{{ newOnPage }} 条新通知</span>
        <span class="nt-result-count">{{ loading ? '读取中' : `${total} 条记录` }}</span>
      </div>
    </section>

    <section class="nt-feed" :aria-busy="loading ? 'true' : 'false'">
      <div v-if="loading" class="nt-skeleton" aria-label="正在读取通知">
        <div v-for="n in 5" :key="n" class="nt-skeleton-row">
          <span></span><div><i></i><b></b><em></em></div>
        </div>
      </div>

      <div v-else-if="error" class="nt-error">
        <div class="nt-empty-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M12 8v5M12 16.5v.5" />
            <circle cx="12" cy="12" r="9" />
          </svg>
        </div>
        <strong>通知暂时无法读取</strong>
        <p>{{ error }}</p>
        <button class="nt-inline-action" type="button" @click="loadNotifications">重新尝试</button>
      </div>

      <div v-else-if="!items.length" class="nt-empty">
        <div class="nt-empty-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
            <path d="M10 21h4" />
          </svg>
        </div>
        <strong>{{ keyword || type !== 'all' ? '没有匹配的通知' : '通知箱还是空的' }}</strong>
        <p>{{ keyword || type !== 'all' ? '换一个关键词，或回到全部通知看看。' : '新的站务公告、系统通知和版本更新会出现在这里。' }}</p>
        <button v-if="keyword || type !== 'all'" class="nt-inline-action" type="button" @click="clearFilters">清除筛选条件</button>
      </div>

      <div v-else class="nt-list">
        <article
          v-for="item in items"
          :key="item.id"
          class="nt-item"
          :class="[`is-${typeInfo(item.type).tone}`, { 'is-pinned': item.pinned, 'is-new': isNew(item) }]"
          tabindex="0"
          role="button"
          @click="openDetail(item)"
          @keydown.enter.prevent="openDetail(item)"
          @keydown.space.prevent="openDetail(item)"
        >
          <div class="nt-date" aria-hidden="true">
            <b>{{ dateParts(item.createdAt).day }}</b>
            <span>{{ dateParts(item.createdAt).month }}</span>
          </div>
          <div class="nt-item-main">
            <div class="nt-item-top">
              <span class="nt-type" :class="`tone-${typeInfo(item.type).tone}`">{{ typeInfo(item.type).label }}</span>
              <span v-if="item.pinned" class="nt-pinned">置顶</span>
              <span v-if="isNew(item)" class="nt-new">NEW</span>
              <time>{{ formatDateTime(item.createdAt) }}</time>
            </div>
            <h3>{{ item.title }}</h3>
            <p>{{ previewOf(item.content) }}</p>
          </div>
          <span class="nt-item-arrow" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </span>
        </article>
      </div>

      <nav v-if="!loading && !error && totalPages > 1" class="nt-pager" aria-label="通知翻页">
        <button type="button" :disabled="page <= 1" @click="goPage(page - 1)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="m15 6-6 6 6 6" />
          </svg>
          上一页
        </button>
        <span><b>{{ page }}</b> / {{ totalPages }}</span>
        <button type="button" :disabled="page >= totalPages" @click="goPage(page + 1)">
          下一页
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="m9 6 6 6-6 6" />
          </svg>
        </button>
      </nav>
    </section>

    <section class="nt-footnote">
      <span class="nt-footnote-mark">i</span>
      <p>通知会同步出现在顶部铃铛里。已读状态保存在当前浏览器，不会影响其他设备。</p>
      <router-link to="/about">了解本站 <span>→</span></router-link>
    </section>

    <Teleport to="body">
      <Transition name="nt-modal">
        <div v-if="detailOpen && selected" class="nt-modal" @click.self="closeDetail">
          <article class="nt-modal-card" role="dialog" aria-modal="true" aria-labelledby="nt-modal-title">
            <header class="nt-modal-head">
              <div class="nt-modal-meta">
                <span class="nt-type" :class="`tone-${typeInfo(selected.type).tone}`">{{ typeInfo(selected.type).label }}</span>
                <span v-if="selected.pinned" class="nt-pinned">置顶</span>
                <time>{{ formatDateTime(selected.createdAt) }}</time>
              </div>
              <button ref="closeButton" class="nt-modal-close" type="button" aria-label="关闭通知" @click="closeDetail">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
                  <path d="m6 6 12 12M18 6 6 18" />
                </svg>
              </button>
            </header>
            <div class="nt-modal-body">
              <span class="nt-modal-eyebrow">SITE SIGNAL / NOTICE #{{ selected.id }}</span>
              <h2 id="nt-modal-title">{{ selected.title }}</h2>
              <p class="nt-modal-content">{{ selected.content }}</p>
            </div>
            <footer class="nt-modal-foot">
              <span>发布于 {{ formatDate(selected.createdAt) }}</span>
              <button class="nt-modal-done" type="button" @click="closeDetail">关闭</button>
            </footer>
          </article>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.nt-page {
  width: min(1080px, 100%);
  margin: 0 auto;
  padding: 34px 0 78px;
  color: var(--ink);
}

.nt-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 306px;
  align-items: stretch;
  gap: 38px;
  padding-bottom: 32px;
  border-bottom: 1px solid var(--line);
}

.nt-hero-copy {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
}

.nt-eyebrow,
.nt-feed-kicker,
.nt-signal-head span,
.nt-signal-head small,
.nt-modal-eyebrow {
  letter-spacing: .12em;
  text-transform: uppercase;
}

.nt-eyebrow {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--accent-ink);
  font-size: 10.5px;
  font-weight: 700;
}

.nt-eyebrow i,
.nt-feed-kicker i {
  font-style: normal;
  color: var(--muted);
}

.nt-signal-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-soft);
}

.nt-hero h1 {
  margin: 19px 0 16px;
  color: var(--ink);
  font-size: clamp(38px, 5.2vw, 58px);
  font-weight: 780;
  letter-spacing: -.055em;
  line-height: .98;
}

.nt-lead {
  max-width: 540px;
  margin: 0;
  color: var(--ink-soft);
  font-size: 14px;
  line-height: 1.85;
}

.nt-lead-break {
  display: block;
}

.nt-hero-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 23px;
  color: var(--muted);
  font-size: 11.5px;
  letter-spacing: .02em;
}

.nt-hero-meta b {
  color: var(--ink-soft);
  font-weight: 650;
}

.nt-meta-dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--muted);
}

.nt-signal-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 238px;
  overflow: hidden;
  padding: 20px 22px 18px;
  border-radius: 16px;
  background: linear-gradient(145deg, #173b67 0%, #102945 55%, #0a1d33 100%);
  color: #eef6ff;
  box-shadow: 0 18px 36px -24px rgba(10, 35, 65, .55);
}

.nt-signal-card::before,
.nt-signal-card::after {
  position: absolute;
  content: "";
  border: 1px solid rgba(170, 207, 255, .2);
  border-radius: 50%;
  pointer-events: none;
}

.nt-signal-card::before {
  width: 210px;
  height: 210px;
  right: -82px;
  bottom: -92px;
  box-shadow: 0 0 0 26px rgba(170, 207, 255, .06), 0 0 0 53px rgba(170, 207, 255, .04);
}

.nt-signal-card::after {
  width: 5px;
  height: 5px;
  right: 56px;
  bottom: 46px;
  background: rgba(170, 207, 255, .75);
  border: 0;
  box-shadow: 0 0 0 5px rgba(170, 207, 255, .12);
}

.nt-signal-head {
  position: relative;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.nt-signal-head span {
  color: #a9c8ed;
  font-size: 9.5px;
  font-weight: 700;
}

.nt-signal-head small {
  color: rgba(222, 239, 255, .56);
  font-size: 9px;
}

.nt-signal-count {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 16px;
}

.nt-signal-count strong {
  color: #b9dcff;
  font-size: 52px;
  font-weight: 740;
  letter-spacing: -.07em;
  line-height: 1;
}

.nt-signal-count span {
  color: rgba(235, 246, 255, .66);
  font-size: 10.5px;
  line-height: 1.5;
}

.nt-signal-rows {
  position: relative;
  display: flex;
  gap: 18px;
  margin-top: 14px;
  color: rgba(229, 242, 255, .58);
  font-size: 10.5px;
}

.nt-signal-rows div {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.nt-signal-rows b {
  color: rgba(239, 248, 255, .92);
  font-size: 12px;
  font-weight: 600;
}

.nt-read-all {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  height: 34px;
  margin-top: auto;
  padding: 0 12px;
  border: 1px solid rgba(172, 208, 255, .26);
  border-radius: 9px;
  background: rgba(255, 255, 255, .08);
  color: #d8ecff;
  font: inherit;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  transition: background .18s ease, border-color .18s ease, opacity .18s ease;
}

.nt-read-all svg {
  width: 15px;
  height: 15px;
}

.nt-read-all:hover:not(:disabled) {
  border-color: rgba(180, 215, 255, .48);
  background: rgba(255, 255, 255, .14);
}

.nt-read-all:disabled {
  cursor: default;
  opacity: .42;
}

.nt-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 22px 0 21px;
}

.nt-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--paper);
}

.nt-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  padding: 0 11px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 11.5px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background .18s ease, color .18s ease;
}

.nt-tab:hover {
  color: var(--ink-soft);
}

.nt-tab.active {
  background: var(--accent);
  color: #fff;
}

.nt-tab em {
  color: inherit;
  font-style: normal;
  font-size: 9.5px;
  font-weight: 600;
  opacity: .68;
}

.nt-tab.active em {
  opacity: .88;
}

.nt-search {
  position: relative;
  display: flex;
  align-items: center;
  width: min(266px, 100%);
  height: 36px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--paper);
  color: var(--muted);
  transition: border-color .18s ease, box-shadow .18s ease;
}

.nt-search:focus-within {
  border-color: color-mix(in srgb, var(--accent) 50%, var(--line));
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.nt-search svg {
  flex: 0 0 16px;
  width: 16px;
  height: 16px;
  margin-right: 8px;
}

.nt-search input {
  min-width: 0;
  flex: 1;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 12.5px;
}

.nt-search input::placeholder {
  color: var(--muted);
}

.nt-search input::-webkit-search-cancel-button {
  display: none;
}

.nt-search-clear {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-left: 4px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--bg-soft);
  color: var(--muted);
  font: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.nt-feed-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding: 11px 2px 15px;
}

.nt-feed-kicker {
  display: block;
  margin-bottom: 5px;
  color: var(--muted);
  font-size: 9px;
  font-weight: 650;
}

.nt-feed-head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 21px;
  font-weight: 720;
  letter-spacing: -.035em;
}

.nt-feed-stats {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

.nt-new-note,
.nt-result-count {
  color: var(--muted);
  font-size: 11px;
}

.nt-new-note {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--accent-ink);
  font-weight: 650;
}

.nt-new-note::before {
  width: 5px;
  height: 5px;
  content: "";
  border-radius: 50%;
  background: var(--accent);
}

.nt-feed {
  min-height: 220px;
}

.nt-list {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: var(--paper);
}

.nt-item {
  position: relative;
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 17px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  outline: none;
  transition: background .18s ease;
}

.nt-item:last-child {
  border-bottom: 0;
}

.nt-item:hover,
.nt-item:focus-visible {
  background: color-mix(in srgb, var(--accent) 5%, var(--paper));
}

.nt-item:hover .nt-item-arrow,
.nt-item:focus-visible .nt-item-arrow {
  border-color: var(--accent);
  color: var(--accent);
  transform: translateX(2px);
}

.nt-item.is-pinned {
  background: color-mix(in srgb, var(--accent) 4%, var(--paper));
}

.nt-item.is-pinned::before {
  position: absolute;
  top: 18px;
  bottom: 18px;
  left: 0;
  width: 2px;
  content: "";
  border-radius: 0 2px 2px 0;
  background: var(--accent);
}

.nt-date {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 52px;
  border-radius: 10px;
  background: var(--bg-soft);
  color: var(--ink-soft);
}

.nt-date b {
  font-size: 18px;
  font-weight: 680;
  letter-spacing: -.04em;
  line-height: 1;
}

.nt-date span {
  margin-top: 4px;
  color: var(--muted);
  font-size: 9px;
  letter-spacing: .02em;
}

.nt-item-main {
  min-width: 0;
}

.nt-item-top {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 18px;
}

.nt-type,
.nt-pinned,
.nt-new {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: 5px;
  font-size: 9.5px;
  font-weight: 650;
  letter-spacing: .01em;
  white-space: nowrap;
}

.nt-type.tone-info {
  background: color-mix(in srgb, #278a68 12%, var(--paper));
  color: #24785c;
}

.nt-type.tone-system {
  background: color-mix(in srgb, #b77718 13%, var(--paper));
  color: #9b6415;
}

.nt-type.tone-update {
  background: var(--accent-soft);
  color: var(--accent-ink);
}

[data-theme="dark"] .nt-type.tone-info {
  color: #77d8b5;
}

[data-theme="dark"] .nt-type.tone-system {
  color: #f3c36e;
}

.nt-pinned {
  background: color-mix(in srgb, #b77718 11%, var(--paper));
  color: #9b6415;
}

[data-theme="dark"] .nt-pinned {
  color: #f3c36e;
}

.nt-new {
  background: var(--accent);
  color: #fff;
  font-size: 8px;
  letter-spacing: .1em;
}

.nt-item time {
  margin-left: auto;
  color: var(--muted);
  font-size: 10px;
  white-space: nowrap;
}

.nt-item-main h3 {
  margin: 7px 0 5px;
  overflow: hidden;
  color: var(--ink);
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -.025em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nt-item-main p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.7;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.nt-item-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--line);
  border-radius: 50%;
  color: var(--muted);
  transition: border-color .18s ease, color .18s ease, transform .18s ease;
}

.nt-item-arrow svg {
  width: 14px;
  height: 14px;
}

.nt-skeleton {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: var(--paper);
}

.nt-skeleton-row {
  display: flex;
  align-items: center;
  gap: 17px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--line);
}

.nt-skeleton-row:last-child {
  border-bottom: 0;
}

.nt-skeleton-row > span {
  width: 56px;
  height: 52px;
  flex: 0 0 56px;
  border-radius: 10px;
  background: var(--bg-soft);
}

.nt-skeleton-row > div {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.nt-skeleton-row i,
.nt-skeleton-row b,
.nt-skeleton-row em {
  display: block;
  height: 9px;
  border-radius: 4px;
  background: linear-gradient(90deg, var(--bg-soft), color-mix(in srgb, var(--bg-soft) 35%, var(--paper)), var(--bg-soft));
  background-size: 200% 100%;
  animation: nt-shimmer 1.3s ease-in-out infinite;
}

.nt-skeleton-row i { width: 31%; height: 7px; }
.nt-skeleton-row b { width: 61%; height: 14px; }
.nt-skeleton-row em { width: 87%; }

@keyframes nt-shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: -100% 0; }
}

.nt-empty,
.nt-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 290px;
  padding: 34px 20px;
  border: 1px dashed var(--line-strong);
  border-radius: 15px;
  text-align: center;
}

.nt-empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 50px;
  height: 50px;
  margin-bottom: 16px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
}

.nt-empty-icon svg {
  width: 22px;
  height: 22px;
}

.nt-empty strong,
.nt-error strong {
  color: var(--ink);
  font-size: 15px;
  font-weight: 650;
}

.nt-empty p,
.nt-error p {
  max-width: 360px;
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.7;
}

.nt-inline-action {
  height: 32px;
  margin-top: 17px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--paper);
  color: var(--accent-ink);
  font: inherit;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

.nt-inline-action:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.nt-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 22px 0 4px;
}

.nt-pager button {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--paper);
  color: var(--ink-soft);
  font: inherit;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}

.nt-pager button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.nt-pager button:disabled {
  cursor: default;
  opacity: .35;
}

.nt-pager button svg {
  width: 15px;
  height: 15px;
}

.nt-pager span {
  color: var(--muted);
  font-size: 11px;
}

.nt-pager span b {
  color: var(--ink);
  font-weight: 650;
}

.nt-footnote {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 32px;
  padding-top: 17px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 11px;
}

.nt-footnote-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  font-family: Georgia, serif;
  font-size: 10px;
  font-style: italic;
}

.nt-footnote p {
  flex: 1;
  margin: 0;
  line-height: 1.7;
}

.nt-footnote a {
  color: var(--accent-ink);
  white-space: nowrap;
}

.nt-footnote a span {
  margin-left: 3px;
}

.nt-modal {
  position: fixed;
  inset: 0;
  z-index: 150;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(5, 16, 29, .55);
  backdrop-filter: blur(8px);
}

.nt-modal-card {
  display: flex;
  flex-direction: column;
  width: min(620px, calc(100vw - 48px));
  max-height: min(760px, calc(100dvh - 48px));
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 19px;
  background: var(--paper);
  box-shadow: 0 32px 80px rgba(6, 24, 45, .32);
}

.nt-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 15px;
  padding: 20px 20px 0 24px;
}

.nt-modal-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  color: var(--muted);
  font-size: 10.5px;
}

.nt-modal-meta time {
  margin-left: 2px;
}

.nt-modal-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.nt-modal-close:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.nt-modal-close svg {
  width: 16px;
  height: 16px;
}

.nt-modal-body {
  overflow-y: auto;
  padding: 22px 24px 26px;
}

.nt-modal-eyebrow {
  color: var(--accent-ink);
  font-size: 9px;
  font-weight: 700;
}

.nt-modal-body h2 {
  margin: 13px 0 16px;
  color: var(--ink);
  font-size: clamp(25px, 3vw, 32px);
  font-weight: 730;
  letter-spacing: -.045em;
  line-height: 1.18;
}

.nt-modal-content {
  margin: 0;
  color: var(--ink-soft);
  font-size: 14.5px;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
}

.nt-modal-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 24px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 10.5px;
}

.nt-modal-done {
  height: 30px;
  padding: 0 13px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--paper);
  color: var(--ink-soft);
  font: inherit;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}

.nt-modal-done:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.nt-modal-enter-active,
.nt-modal-leave-active {
  transition: opacity .18s ease;
}

.nt-modal-enter-active .nt-modal-card,
.nt-modal-leave-active .nt-modal-card {
  transition: transform .24s cubic-bezier(.22, 1, .36, 1);
}

.nt-modal-enter-from,
.nt-modal-leave-to {
  opacity: 0;
}

.nt-modal-enter-from .nt-modal-card,
.nt-modal-leave-to .nt-modal-card {
  transform: translateY(12px) scale(.985);
}

@media (max-width: 860px) {
  .nt-page {
    padding: 18px 0 58px;
  }

  .nt-hero {
    grid-template-columns: minmax(0, 1fr);
    gap: 19px;
    padding-bottom: 21px;
  }

  .nt-hero h1 {
    margin: 15px 0 12px;
    font-size: clamp(34px, 9.5vw, 46px);
  }

  .nt-lead {
    font-size: 13px;
    line-height: 1.75;
  }

  .nt-lead-break {
    display: none;
  }

  .nt-hero-meta {
    margin-top: 17px;
    font-size: 10.5px;
  }

  .nt-signal-card {
    min-height: 0;
    flex-direction: row;
    align-items: center;
    gap: 10px;
    padding: 13px 14px;
    border-radius: 13px;
  }

  .nt-signal-head,
  .nt-signal-rows {
    display: none;
  }

  .nt-signal-count {
    gap: 6px;
    margin: 0;
  }

  .nt-signal-count strong {
    font-size: 33px;
  }

  .nt-signal-count span {
    font-size: 9.5px;
  }

  .nt-read-all {
    width: auto;
    height: 32px;
    margin: 0 0 0 auto;
    padding: 0 10px;
    font-size: 10.5px;
  }

  .nt-read-all svg {
    width: 13px;
    height: 13px;
  }

  .nt-toolbar {
    display: block;
    padding: 16px 0 14px;
  }

  .nt-tabs {
    width: 100%;
    overflow-x: auto;
    padding: 3px;
    border-radius: 12px;
    scrollbar-width: none;
  }

  .nt-tabs::-webkit-scrollbar {
    display: none;
  }

  .nt-tab {
    flex: 1;
    justify-content: center;
    min-width: 0;
    min-height: 31px;
    padding: 0 5px;
    font-size: 10.5px;
    gap: 4px;
  }

  .nt-search {
    width: 100%;
    height: 38px;
    margin-top: 9px;
  }

  .nt-feed-head {
    padding: 8px 1px 12px;
  }

  .nt-feed-head h2 {
    font-size: 19px;
  }

  .nt-item {
    grid-template-columns: 44px minmax(0, 1fr);
    gap: 12px;
    padding: 15px 14px;
  }

  .nt-date {
    width: 44px;
    height: 44px;
    border-radius: 9px;
  }

  .nt-date b {
    font-size: 16px;
  }

  .nt-date span {
    margin-top: 3px;
    font-size: 8px;
  }

  .nt-item-top {
    flex-wrap: wrap;
    gap: 5px;
  }

  .nt-item time {
    font-size: 9px;
  }

  .nt-item-main h3 {
    display: -webkit-box;
    overflow: hidden;
    margin: 6px 0 4px;
    font-size: 15px;
    line-height: 1.35;
    white-space: normal;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  .nt-item-main p {
    font-size: 11.5px;
    line-height: 1.65;
  }

  .nt-item-arrow {
    display: none;
  }

  .nt-skeleton-row {
    gap: 12px;
    padding: 15px 14px;
  }

  .nt-skeleton-row > span {
    width: 44px;
    height: 44px;
    flex-basis: 44px;
  }

  .nt-empty,
  .nt-error {
    min-height: 260px;
    padding: 30px 18px;
  }

  .nt-pager {
    gap: 12px;
    padding-top: 18px;
  }

  .nt-footnote {
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 25px;
  }

  .nt-footnote p {
    flex-basis: calc(100% - 26px);
    font-size: 10.5px;
  }

  .nt-footnote a {
    width: 100%;
    margin-left: 26px;
    font-size: 10.5px;
  }

  .nt-modal {
    align-items: flex-end;
    padding: 8px;
  }

  .nt-modal-card {
    width: 100%;
    max-height: calc(100dvh - 16px);
    border-radius: 18px;
  }

  .nt-modal-head {
    padding: 17px 14px 0 18px;
  }

  .nt-modal-body {
    padding: 19px 18px 23px;
  }

  .nt-modal-body h2 {
    font-size: clamp(22px, 7vw, 28px);
  }

  .nt-modal-content {
    font-size: 13.5px;
    line-height: 1.85;
  }

  .nt-modal-foot {
    padding: 12px 18px;
  }
}

@media (max-width: 390px) {
  .nt-tab {
    gap: 3px;
    padding: 0 3px;
    font-size: 10px;
  }

  .nt-tab em {
    font-size: 8.5px;
  }

  .nt-signal-count strong {
    font-size: 30px;
  }

  .nt-read-all {
    padding: 0 8px;
    font-size: 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .nt-skeleton-row i,
  .nt-skeleton-row b,
  .nt-skeleton-row em {
    animation: none;
  }

  .nt-item,
  .nt-item-arrow,
  .nt-search,
  .nt-search-clear,
  .nt-read-all,
  .nt-modal,
  .nt-modal-card {
    transition: none;
  }
}
</style>
