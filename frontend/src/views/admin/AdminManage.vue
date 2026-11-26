<script setup>
import { computed, markRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { hasPermission } from '../../state'
import AdminPosts from './AdminPosts.vue'
import AdminComments from './AdminComments.vue'
import AdminTags from './AdminTags.vue'
import AdminAnnouncements from './AdminAnnouncements.vue'
import AdminNavigation from './AdminNavigation.vue'

const route = useRoute()
const router = useRouter()

const modules = [
  { key: 'posts', label: '文章管理', short: '文章', permission: 'system:posts', component: markRaw(AdminPosts), icon: 'posts' },
  { key: 'comments', label: '评论管理', short: '评论', permission: 'system:comments', component: markRaw(AdminComments), icon: 'comments' },
  { key: 'tags', label: '标签管理', short: '标签', permission: 'system:tags', component: markRaw(AdminTags), icon: 'tags' },
  { key: 'announcements', label: '公告管理', short: '公告', permission: 'system:announcements', component: markRaw(AdminAnnouncements), icon: 'announcements' },
  { key: 'navigation', label: '导航管理', short: '导航', permission: 'system:navigation', component: markRaw(AdminNavigation), icon: 'navigation' }
]

const visibleModules = computed(() => modules.filter((item) => hasPermission(item.permission)))
const activeKey = computed(() => {
  const key = String(route.query.tab || '')
  return visibleModules.value.some((item) => item.key === key) ? key : visibleModules.value[0]?.key
})
const activeModule = computed(() => visibleModules.value.find((item) => item.key === activeKey.value))
const statusTabs = computed(() => ({
  posts: [
    { key: 'all', label: '全部文章' },
    { key: 'published', label: '已发布' },
    { key: 'draft', label: '草稿' },
    { key: 'pending', label: '待审核' },
    { key: 'rejected', label: '已退回' }
  ],
  comments: [
    { key: 'all', label: '全部评论' },
    { key: 'pending', label: '待审核' },
    { key: 'visible', label: '已展示' },
    { key: 'hidden', label: '已隐藏' }
  ]
}[activeKey.value] || []))
const activeView = computed(() => {
  const key = String(route.query.view || 'all')
  return statusTabs.value.some((view) => view.key === key) ? key : 'all'
})
const activeProps = computed(() => {
  if (activeKey.value === 'posts' || activeKey.value === 'comments') {
    return { statusTab: activeView.value }
  }
  return {}
})

function selectModule(module) {
  router.replace({ path: route.path, query: { tab: module.key } })
}

function selectView(view) {
  router.replace({ path: route.path, query: { ...route.query, tab: activeKey.value, view: view.key } })
}
</script>

<template>
  <div class="adm-page manage-page">
    <section class="adm-card manage-intro">
      <div>
        <span class="adm-card-kicker">CONTENT OPERATIONS</span>
        <h2>管理</h2>
        <p>文章、评论、标签、公告和导航集中在这里。切换下方模块即可处理对应内容，不需要在侧边栏来回跳转。</p>
      </div>
      <span class="adm-total-pill">{{ visibleModules.length }} 个模块</span>
    </section>

    <nav v-if="visibleModules.length" class="manage-tabs" aria-label="管理模块">
      <button
        v-for="module in visibleModules"
        :key="module.key"
        type="button"
        :class="{ on: activeKey === module.key }"
        @click="selectModule(module)"
      >
        {{ module.short }}
      </button>
    </nav>

    <div v-if="statusTabs.length" class="manage-filter-row">
      <div class="manage-status-tabs" role="tablist" :aria-label="`${activeModule?.label || '内容'}筛选`">
        <button
          v-for="view in statusTabs"
          :key="view.key"
          type="button"
          role="tab"
          :aria-selected="activeView === view.key"
          :class="{ on: activeView === view.key }"
          @click="selectView(view)"
        >{{ view.label }}</button>
      </div>
      <span class="meta">共 {{ statusTabs.length }} 种状态视图</span>
    </div>

    <component :is="activeModule?.component" v-if="activeModule" v-bind="activeProps" />

    <section v-else class="adm-card manage-empty">
      <h2>当前角色没有可用的管理模块</h2>
      <p>请联系管理员在「角色权限」中授予文章、评论、标签、公告或导航权限。</p>
    </section>
  </div>
</template>

<style scoped>
.manage-intro { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 14px; }
.manage-intro h2 { margin: 5px 0 4px; font-size: 21px; color: var(--ink); }
.manage-intro p { max-width: 760px; margin: 0; color: var(--muted); font-size: 12.5px; line-height: 1.7; }
.manage-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; padding: 6px; border: 1px solid var(--line); border-radius: 12px; background: var(--paper); }
.manage-tabs button { flex: 1 1 96px; padding: 9px 14px; border: 0; border-radius: 8px; background: transparent; color: var(--muted); font-size: 13px; font-weight: 600; cursor: pointer; transition: background .18s ease, color .18s ease, box-shadow .18s ease; }
.manage-tabs button:hover { color: var(--ink); background: color-mix(in srgb, var(--accent) 5%, var(--paper)); }
.manage-tabs button.on { background: var(--accent); color: #fff; box-shadow: 0 4px 12px color-mix(in srgb, var(--accent) 22%, transparent); }
.manage-filter-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.manage-status-tabs { display: flex; flex-wrap: wrap; gap: 4px; padding: 3px; border: 1px solid color-mix(in srgb, var(--line) 78%, transparent); border-radius: 999px; background: var(--paper); }
.manage-status-tabs button { padding: 5px 11px; border: 0; border-radius: 999px; background: transparent; color: var(--muted); font-size: 11.5px; cursor: pointer; }
.manage-status-tabs button.on { background: var(--accent); color: #fff; }
.manage-empty { text-align: center; padding: 48px 24px; }
.manage-empty h2 { color: var(--ink); font-size: 18px; }
.manage-empty p { margin: 8px 0 0; color: var(--muted); font-size: 12.5px; }
@media (max-width: 760px) { .manage-intro, .manage-filter-row { flex-direction: column; align-items: flex-start; } .manage-tabs button { flex-basis: calc(50% - 4px); } }
</style>
