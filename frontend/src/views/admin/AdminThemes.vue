<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../../api'
import { applyTheme, applyThemePreset, state, toast } from '../../state'

const themes = ref([])
const current = ref('ink')
const loading = ref(false)
const applying = ref('')
const active = computed(() => themes.value.find((t) => t.id === current.value))

const swatches = [
  { key: 'accent', label: '强调色' },
  { key: 'paper', label: '纸张' },
  { key: 'ink', label: '正文' }
]

async function load() {
  loading.value = true
  try {
    const res = await http.get('/admin/themes')
    themes.value = res.data.themes || []
    current.value = res.data.current || 'ink'
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function activate(theme) {
  if (theme.id === current.value) return
  applying.value = theme.id
  try {
    await http.post(`/admin/themes/${theme.id}/activate`)
    current.value = theme.id
    state.site.themePreset = theme.id
    applyThemePreset(theme.id)
    if (theme.mode === 'dark' || theme.mode === 'light') {
      applyTheme(theme.mode)
    }
    try {
      state.site = { ...state.site, ...(await http.get('/meta/site')).data }
    } catch (e) { /* 元信息刷新失败不阻塞 */ }
    toast(`已应用「${theme.name}」主题`)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    applying.value = ''
  }
}

function themeModeLabel(mode) {
  return mode === 'dark' ? '深色' : mode === 'light' ? '浅色' : '跟随系统'
}
onMounted(load)
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">当前主题：{{ active?.name || '墨记' }} · {{ themes.length }} 套内置主题</span>
      <div class="adm-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading" @click="load">刷新</button>
      </div>
    </div>

    <section class="theme-hero">
      <div>
        <span class="adm-card-kicker">SITE APPEARANCE</span>
        <h2>主题库</h2>
        <p>主题决定站点强调色、纸张底色与深浅模式。应用后立即对全站生效；读者仍可在导航栏切换明暗。</p>
      </div>
      <div class="theme-hero-chip">
        <span class="theme-chip-dot" :style="{ background: active?.accent || '#24589f' }"></span>
        {{ active?.name || '墨记' }}
        <small>{{ themeModeLabel(active?.mode || 'auto') }}</small>
      </div>
    </section>

    <div v-if="loading" class="adm-loading">正在读取主题…</div>

    <section v-else class="theme-grid">
      <article
        v-for="theme in themes"
        :key="theme.id"
        class="theme-card"
        :class="{ current: theme.id === current }"
      >
        <div class="theme-preview" :data-preview="theme.id">
          <div class="theme-browser">
            <span></span><span></span><span></span>
          </div>
          <div class="theme-page">
            <div class="theme-page-nav">
              <span class="theme-logo" :style="{ background: theme.accent }"></span>
              <i></i><i></i><i></i>
            </div>
            <div class="theme-page-hero">
              <u :style="{ background: theme.accent }"></u>
              <b></b><b></b>
            <div class="theme-page-cards"><span></span><span></span><span></span></div>
            </div>
          </div>
          <span v-if="theme.id === current" class="theme-current-badge">使用中</span>
        </div>

        <div class="theme-card-body">
          <div class="theme-card-head">
            <div>
              <h3>{{ theme.name }}</h3>
              <small>{{ theme.id }} · {{ themeModeLabel(theme.mode) }}</small>
            </div>
            <div class="theme-swatches">
              <span v-for="s in swatches" :key="s.key" :title="`${s.label}：${theme[s.key] || '随明暗模式'}`" :style="{ background: theme[s.key] || theme.accent }"></span>
            </div>
          </div>
          <p>{{ theme.description }}</p>
          <div class="theme-card-foot">
            <span class="theme-builtin">内置 · 无需安装</span>
            <button
              class="btn btn-sm"
              type="button"
              :disabled="Boolean(applying) || theme.id === current"
              @click="activate(theme)"
            >{{ applying === theme.id ? '应用中…' : theme.id === current ? '当前主题' : '应用主题' }}</button>
          </div>
        </div>
      </article>
    </section>

    <section class="adm-card theme-notes">
      <div class="adm-card-head">
        <div><span class="adm-card-kicker">ABOUT THEMES</span><h2>主题说明</h2></div>
      </div>
      <div class="theme-note-grid">
        <div><strong>即时生效</strong><span>应用后写入站点配置，前台与后台同步刷新。</span></div>
        <div><strong>不覆盖自定义</strong><span>站点 Logo、封面与文章内容不受主题切换影响。</span></div>
        <div><strong>深浅模式</strong><span>浅色主题会切到浅色模式；「墨记」跟随读者系统设置。</span></div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.theme-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 24px 26px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); }
.theme-hero h2 { margin: 6px 0 6px; font-size: 22px; color: var(--ink); }
.theme-hero p { max-width: 620px; margin: 0; color: var(--muted); font-size: 13px; line-height: 1.7; }
.theme-hero-chip { flex: 0 0 auto; display: flex; align-items: center; gap: 8px; padding: 9px 14px; border: 1px solid var(--line); border-radius: 999px; color: var(--ink-soft); font-size: 13px; }
.theme-hero-chip small { color: var(--muted); }
.theme-chip-dot { width: 10px; height: 10px; border-radius: 50%; }
.theme-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; margin-top: 18px; }
.theme-card { overflow: hidden; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease; }
.theme-card:hover { transform: translateY(-2px); box-shadow: 0 14px 40px rgba(15, 40, 80, .08); }
.theme-card.current { border-color: var(--accent); }
.theme-preview { position: relative; height: 170px; padding: 18px 16px 0; overflow: hidden; background: linear-gradient(150deg, #eef4ff, #f8fafc); }
.theme-preview[data-preview="paper"] { background: linear-gradient(150deg, #f4ecdc, #fbf8f1); }
.theme-preview[data-preview="forest"] { background: linear-gradient(150deg, #e4f0eb, #f4faf7); }
.theme-preview[data-preview="ocean"] { background: linear-gradient(150deg, #e3f2fa, #f4fafe); }
.theme-preview[data-preview="rose"] { background: linear-gradient(150deg, #f8e8ed, #fff7f9); }
.theme-preview[data-preview="night"] { background: linear-gradient(150deg, #101e32, #0a1322); }
.theme-browser { display: flex; gap: 5px; margin-bottom: 8px; }
.theme-browser span { width: 6px; height: 6px; border-radius: 50%; background: rgba(15, 40, 80, .13); }
.theme-page { height: 145px; border-radius: 8px 8px 0 0; background: #fff; box-shadow: 0 8px 26px rgba(15, 40, 80, .12); overflow: hidden; }
.theme-preview[data-preview="night"] .theme-page { background: #142338; }
.theme-page-nav { display: flex; align-items: center; gap: 8px; padding: 9px 11px; border-bottom: 1px solid rgba(15, 40, 80, .06); }
.theme-page-nav i { width: 16px; height: 3px; border-radius: 2px; background: rgba(15, 40, 80, .12); }
.theme-logo { width: 8px; height: 8px; border-radius: 2px; }
.theme-page-hero { padding: 18px 13px; }
.theme-page-hero u { display: block; width: 64%; height: 7px; border-radius: 3px; opacity: .92; }
.theme-page-hero b { display: block; width: 84%; height: 3px; margin-top: 7px; border-radius: 2px; background: rgba(15, 40, 80, .12); }
.theme-page-hero b + b { width: 54%; }
.theme-page-cards { display: flex; gap: 6px; margin-top: 14px; }
.theme-page-cards span { flex: 1; height: 34px; border-radius: 5px; background: rgba(15, 40, 80, .05); border: 1px solid rgba(15, 40, 80, .07); }
.theme-current-badge { position: absolute; top: 16px; right: 16px; padding: 3px 8px; border-radius: 999px; background: var(--accent); color: #fff; font-size: 10px; letter-spacing: .04em; }
.theme-card-body { padding: 16px 18px 18px; }
.theme-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.theme-card-head h3 { margin: 0; font-size: 17px; color: var(--ink); }
.theme-card-head small { color: var(--muted); font-size: 11px; }
.theme-swatches { display: flex; gap: 4px; padding-top: 4px; }
.theme-swatches span { width: 13px; height: 13px; border-radius: 50%; border: 1px solid rgba(15, 40, 80, .13); }
.theme-card-body p { min-height: 44px; margin: 9px 0 14px; color: var(--muted); font-size: 12px; line-height: 1.7; }
.theme-card-foot { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.theme-builtin { color: var(--muted); font-size: 11px; }
.theme-notes { margin-top: 20px; }
.theme-note-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.theme-note-grid div { display: grid; gap: 5px; }
.theme-note-grid strong { color: var(--ink); font-size: 13px; }
.theme-note-grid span { color: var(--muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 1000px) { .theme-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 680px) { .theme-grid, .theme-note-grid { grid-template-columns: 1fr; } .theme-hero { align-items: flex-start; flex-direction: column; } }
</style>
