<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { state, toast } from '../../state'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'basic'))
const config = ref({})
const loading = ref(false)
const saving = ref(false)
const uploadingLogo = ref(false)
const uploadingAvatar = ref(false)
const logoInput = ref(null)
const avatarInput = ref(null)
const logoFailed = ref(false)
const avatarFailed = ref(false)
const sourcePreview = ref(null)
const sourceLoading = ref(false)
const sourceError = ref('')
let sourceSeq = 0
const platformLabelMap = {
  wy: '网易音乐', tx: 'QQ 音乐', kw: '酷我音乐', kg: '酷狗音乐', mg: '咪咕音乐'
}
const showLogoPreview = computed(() => Boolean(config.value.logo) && !logoFailed.value)
const showAvatarPreview = computed(() => Boolean(config.value.avatar) && !avatarFailed.value)
const previewFallback = computed(() => (config.value.siteName || '墨').trim().slice(0, 1) || '墨')
const sourceUrl = computed({
  get: () => config.value.musicSourceUrl || '',
  set: (value) => { config.value.musicSourceUrl = value }
})
const sourcePlatforms = computed(() => {
  if (Array.isArray(sourcePreview.value?.platforms)) {
    return sourcePreview.value.platforms.map((item) => ({
      code: item.code,
      label: item.label || item.name || platformLabelMap[item.code] || item.code,
      qualities: item.qualities || []
    }))
  }
  return String(config.value.musicSourcePlatforms || '')
    .split(',')
    .map((code) => code.trim())
    .filter(Boolean)
    .map((code) => ({ code, label: platformLabelMap[code] || code, qualities: [] }))
})
const sourceQualities = computed(() => {
  const list = sourcePreview.value?.qualities || String(config.value.musicSourceQualities || '').split(',')
  return String(list.join(','))
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
})
const sourceReady = computed(() => Boolean(config.value.musicSourceName && config.value.musicSourceVersion))
const sourceStatusText = computed(() => {
  if (sourceLoading.value) return '正在下载并运行音源脚本…'
  if (sourceError.value) return sourceError.value
  if (sourceReady.value) return `${config.value.musicSourceName} · v${config.value.musicSourceVersion}`
  if (sourceUrl.value.trim()) return '已填写地址，点击“导入并校验”验证音源'
  return '尚未导入音源'
})
const sourceTestText = computed(() => {
  if (sourceLoading.value) return '校验中'
  if (sourceError.value) return '校验失败'
  if (sourcePreview.value?.tested) return '播放测试通过'
  if (sourcePreview.value) return '已初始化'
  return '待校验'
})
const sourceTestDetail = computed(() => sourcePreview.value?.testMessage || '')
const sourceVersionLabel = computed(() => config.value.musicSourceVersion ? `v${config.value.musicSourceVersion}` : '')
const sourceDescription = computed(() => config.value.musicSourceDescription || 'LX Music 自定义音源脚本')


function boolValue(value) {
  return String(value).toLowerCase() === 'true'
}

function setBool(key, event) {
  config.value[key] = event.target.checked ? 'true' : 'false'
}

async function previewSource(force = false, silent = false) {
  const url = sourceUrl.value.trim()
  if (!url) {
    sourcePreview.value = null
    sourceError.value = ''
    return false
  }
  const seq = ++sourceSeq
  sourceLoading.value = true
  sourceError.value = ''
  try {
    const res = await http.get('/admin/music/source/preview', {
      params: { url, refresh: force },
      timeout: 45000
    })
    if (seq !== sourceSeq) return false
    const data = res.data || {}
    sourcePreview.value = data
    config.value.musicSourceUrl = data.url || url
    config.value.musicSourceName = data.name || ''
    config.value.musicSourceVersion = data.version || ''
    config.value.musicSourceDescription = data.description || ''
    config.value.musicSourcePlatforms = (data.platforms || []).map((item) => item.code).join(',')
    config.value.musicSourceQualities = (data.qualities || []).join(',')
    config.value.musicSourceCheckedAt = new Date().toISOString()
    if (!silent) toast(data.tested ? '音源校验通过，已测试播放地址' : '音源已初始化，保存后可搜索播放')
    return true
  } catch (e) {
    if (seq !== sourceSeq) return false
    sourcePreview.value = null
    sourceError.value = e.message || '音源校验失败'
    if (!silent) toast(sourceError.value, 'error')
    return false
  } finally {
    if (seq === sourceSeq) sourceLoading.value = false
  }
}

function useSixyinSource() {
  config.value.musicSourceUrl = 'https://gh-proxy.org/https://raw.githubusercontent.com/pdone/lx-music-source/main/sixyin/latest.js'
  sourcePreview.value = null
  sourceError.value = ''
}

async function load() {
  loading.value = true
  try {
    const data = (await http.get('/admin/config')).data || {}
    config.value = data
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function save() {
  const url = sourceUrl.value.trim()
  if (url && (!sourceReady.value || url !== String(config.value.musicSourceUrl || '').trim())) {
    const valid = await previewSource(true, true)
    if (!valid) {
      toast('音源尚未校验通过，请先点击“导入并校验”', 'error')
      return
    }
  }
  saving.value = true
  try {
    config.value.musicSourceUrl = url
    const res = await http.put('/admin/config', config.value)
    config.value = res.data
    try {
      const meta = await http.get('/meta/site')
      state.site = { ...state.site, ...meta.data }
    } catch (e) {
      /* 前台元信息刷新失败不阻塞保存结果 */
    }
    toast('站点音源已保存，前台立即生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

function pickImage(target) {
  if (target === 'logo') logoInput.value?.click()
  if (target === 'avatar') avatarInput.value?.click()
}

async function uploadImage(event, key) {
  const file = event.target.files?.[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  if (key === 'logo') uploadingLogo.value = true
  if (key === 'avatar') uploadingAvatar.value = true
  try {
    const res = await http.post('/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    config.value[key] = res.data.url
    toast(key === 'logo' ? 'Logo 已上传，保存后生效' : '站点头像已上传，保存后生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    if (key === 'logo') uploadingLogo.value = false
    if (key === 'avatar') uploadingAvatar.value = false
    event.target.value = ''
  }
}

onBeforeUnmount(() => {
  sourceSeq += 1
})

onMounted(load)
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">保存后前台立即更新</span>
      <div class="adm-head-actions">
        <button class="btn btn-sm" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存站点信息' }}</button>
      </div>
    </div>

    <div v-if="loading" class="adm-loading">正在读取站点配置…</div>

    <template v-else>
      <div v-if="activeTab === 'basic'" class="adm-settings-grid">
        <section class="adm-card">
          <div class="adm-card-head">
            <div><span class="adm-card-kicker">BASIC</span><h2>基础信息</h2></div>
          </div>
          <div class="form-grid">
            <div class="field">
              <label for="siteName">站点名称</label>
              <input id="siteName" v-model="config.siteName" type="text" maxlength="32" />
            </div>
            <div class="field">
              <label for="siteKeywords">站点关键词</label>
              <input id="siteKeywords" v-model="config.siteKeywords" type="text" placeholder="用逗号分隔" />
            </div>
            <div class="field span-2">
              <label for="siteDesc">站点描述</label>
              <input id="siteDesc" v-model="config.siteDesc" type="text" maxlength="200" />
            </div>
            <div class="field span-2">
              <label for="heroTitle">首页主标题 <span class="meta">留空使用站点名称</span></label>
              <input id="heroTitle" v-model="config.heroTitle" maxlength="64" placeholder="例如 在代码与生活之间写字" />
            </div>
            <div class="field span-2">
              <label for="heroSubtitle">首页副标题 <span class="meta">留空使用站点描述</span></label>
              <textarea id="heroSubtitle" v-model="config.heroSubtitle" maxlength="200" style="min-height: 64px"></textarea>
            </div>
            <div class="field span-2">
              <label>首页公告</label>
              <div class="site-announcement-hint">
                <span>{{ boolValue(config.announcementEnabled) ? '公告已开启' : '公告未开启' }}</span>
                <router-link class="btn-text" to="/admin/announcements">去公告管理编辑、AI 生成 →</router-link>
              </div>
            </div>
          </div>
        </section>
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">TIP</span><h2>站点身份</h2></div></div>
            <p class="adm-note">站点名称会出现在浏览器标题、导航、页脚和后台左上角；修改后无需重启。</p>
            <div class="adm-site-preview">
              <span class="adm-site-preview-mark">
                <img v-if="showLogoPreview" :src="config.logo" alt="" @error="logoFailed = true" />
                <template v-else>{{ previewFallback }}</template>
              </span>
              <strong>{{ config.siteName || '未命名站点' }}</strong>
            </div>
          </section>
        </aside>
      </div>

      <div v-else-if="activeTab === 'appearance'" class="adm-settings-grid">
        <section class="adm-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">BRAND</span><h2>品牌与关于</h2></div></div>
          <div class="form-grid">
            <div class="field span-2">
              <label>站点 Logo</label>
              <div class="adm-media-row">
                <span class="adm-logo-preview">
                  <img v-if="showLogoPreview" :src="config.logo" alt="" @error="logoFailed = true" />
                  <template v-else>{{ previewFallback }}</template>
                </span>
                <div>
                  <button class="btn btn-ghost btn-sm" type="button" :disabled="uploadingLogo" @click="pickImage('logo')">{{ uploadingLogo ? '上传中…' : '上传 Logo' }}</button>
                  <button v-if="config.logo" class="btn-text" type="button" @click="config.logo = ''">移除</button>
                  <p class="meta">建议透明底或方形图片，显示在导航与后台左上角。</p>
                  <input ref="logoInput" type="file" accept="image/*" hidden @change="uploadImage($event, 'logo')" />
                </div>
              </div>
            </div>
            <div class="field span-2">
              <label>站点头像</label>
              <div class="adm-media-row">
                <span class="avatar lg">
                  <img v-if="showAvatarPreview" :src="config.avatar" alt="" @error="avatarFailed = true" />
                  <template v-else>{{ previewFallback }}</template>
                </span>
                <div>
                  <button class="btn btn-ghost btn-sm" type="button" :disabled="uploadingAvatar" @click="pickImage('avatar')">{{ uploadingAvatar ? '上传中…' : '上传头像' }}</button>
                  <button v-if="config.avatar" class="btn-text" type="button" @click="config.avatar = ''">移除</button>
                  <p class="meta">用于首页资料卡与站点身份展示。</p>
                  <input ref="avatarInput" type="file" accept="image/*" hidden @change="uploadImage($event, 'avatar')" />
                </div>
              </div>
            </div>
            <div class="field span-2">
              <label for="about">关于页内容（支持 Markdown）</label>
              <textarea id="about" v-model="config.about" style="min-height: 150px"></textarea>
            </div>
          </div>
        </section>
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">LOGO</span><h2>导航预览</h2></div></div>
            <div class="adm-brand-preview">
              <span class="adm-site-preview-mark large">
                <img v-if="showLogoPreview" :src="config.logo" alt="" @error="logoFailed = true" />
                <template v-else>{{ previewFallback }}</template>
              </span>
              <strong>{{ config.siteName || '墨记' }}</strong>
            </div>
          </section>
        </aside>
      </div>

      <div v-else-if="activeTab === 'music'" class="adm-settings-grid music-settings-grid">
        <section class="adm-card music-config-card">
          <div class="adm-card-head">
            <div>
              <span class="adm-card-kicker">LX SOURCE</span>
              <h2>站点音乐</h2>
              <p>导入 LX Music 自定义音源脚本（.js）。音源负责解析播放地址，站点搜索使用网易云公开曲库，前台悬浮窗提供完整播放器。</p>
            </div>
          </div>

          <div class="form-grid">
            <div class="field span-2">
              <label class="adm-switch-label">
                <span>启用站点音乐</span>
                <input type="checkbox" :checked="boolValue(config.musicEnabled)" @change="setBool('musicEnabled', $event)" />
                <i class="adm-switch" aria-hidden="true"></i>
              </label>
              <p class="adm-note">浏览器禁止自动播放。读者点击右下角悬浮按钮展开播放器后，再点播放才会开始，不会强制打扰浏览。</p>
            </div>

            <div class="field span-2">
              <label for="musicSourceUrl">音源脚本地址</label>
              <div class="music-link-row">
                <input
                  id="musicSourceUrl"
                  v-model="sourceUrl"
                  type="text"
                  placeholder="https://…/latest.js"
                  spellcheck="false"
                />
                <button class="btn btn-ghost btn-sm" type="button" :disabled="sourceLoading || !sourceUrl.trim()" @click="previewSource(true)">
                  {{ sourceLoading ? '校验中…' : '导入并校验' }}
                </button>
              </div>
              <div class="music-source-actions">
                <button class="btn-text" type="button" @click="useSixyinSource">填入六音音源地址</button>
                <span class="meta">支持 LX Music 2.0 自定义源；脚本会下载到本站并执行初始化校验。</span>
              </div>
            </div>

            <div class="field span-2">
              <label>音源状态</label>
              <div class="music-source-panel" :class="{ loading: sourceLoading, error: sourceError, ready: sourceReady && !sourceError }">
                <div class="music-source-head">
                  <span class="music-source-orb">{{ sourceReady ? '♪' : 'JS' }}</span>
                  <div class="music-source-copy">
                    <strong>{{ sourceReady ? config.musicSourceName : '尚未导入音源' }}</strong>
                    <small>{{ sourceStatusText }}</small>
                  </div>
                  <span class="music-source-state" :class="{ ok: sourceReady && !sourceError, failed: sourceError }">
                    {{ sourceTestText }}
                  </span>
                </div>

                <p v-if="sourceTestDetail" class="music-source-note" :class="{ danger: sourceError }">{{ sourceTestDetail }}</p>

                <div v-if="sourceReady" class="music-source-meta">
                  <div>
                    <span>版本</span>
                    <strong>{{ sourceVersionLabel || '—' }}</strong>
                  </div>
                  <div>
                    <span>音质</span>
                    <strong>{{ sourceQualities.length ? sourceQualities.join(' / ') : '—' }}</strong>
                  </div>
                  <div>
                    <span>脚本</span>
                    <strong>{{ sourceDescription }}</strong>
                  </div>
                </div>

                <div v-if="sourcePlatforms.length" class="music-source-platforms">
                  <span>支持平台</span>
                  <div>
                    <span v-for="platform in sourcePlatforms" :key="platform.code" class="music-platform-chip">
                      {{ platform.label }}
                    </span>
                  </div>
                </div>

                <div v-if="sourcePreview?.testTrack" class="music-source-test">
                  <span class="music-source-test-eq" aria-hidden="true"><i></i><i></i><i></i></span>
                  <div>
                    <strong>测试曲目：{{ sourcePreview.testTrack.title }}</strong>
                    <small>{{ sourcePreview.testTrack.artist }} · 播放地址已返回</small>
                  </div>
                  <span>PASS</span>
                </div>

                <div class="music-source-actions">
                  <button v-if="sourceError" class="btn btn-ghost btn-sm" type="button" @click="previewSource(true)">重新校验</button>
                  <a v-if="sourcePreview?.homepage" :href="sourcePreview.homepage.startsWith('http') ? sourcePreview.homepage : 'https://' + sourcePreview.homepage" target="_blank" rel="noopener" class="btn-text">音源主页 ↗</a>
                </div>
              </div>
            </div>
          </div>
        </section>

        <aside class="adm-side-stack">
          <section class="adm-card music-preview-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">PLAYER</span><h2>前台播放器预览</h2></div></div>
            <div class="music-app-preview">
              <div class="music-app-preview-head">
                <span class="music-app-preview-cover">♪</span>
                <div>
                  <strong>搜索一首歌</strong>
                  <small>{{ config.musicSourceName ? config.musicSourceName + ' · 已就绪' : '等待音源' }}</small>
                </div>
                <span class="music-app-preview-eq" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
              </div>
              <div class="music-app-preview-search">搜索歌曲、歌手或专辑</div>
              <div class="music-app-preview-controls">
                <span>随机</span><strong>▶</strong><span>列表循环</span>
              </div>
              <div class="music-app-preview-bar"><i></i></div>
            </div>
            <p class="adm-note">{{ boolValue(config.musicEnabled) ? '已启用。保存后读者可在前台搜索、切歌、调音量、切换随机/单曲/列表模式。' : '尚未启用，保存并打开开关后才会显示。' }}</p>
          </section>

          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">TIPS</span><h2>音源使用说明</h2></div></div>
            <ul class="music-tips">
              <li>一个音源地址即可，不需要再粘贴歌单链接。</li>
              <li>音源协议只提供播放地址，搜索固定使用网易云公开曲库。</li>
              <li>受版权限制的歌曲会提示失败，不绕过平台限制。</li>
              <li>脚本运行在隔离 JS 引擎中，不开放文件、进程或 Java 权限。</li>
            </ul>
          </section>
        </aside>
      </div>

      <div v-else-if="activeTab === 'footer'" class="adm-settings-grid">
        <section class="adm-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">FOOTER</span><h2>页脚与联系</h2></div></div>
          <div class="form-grid">
            <div class="field">
              <label for="footer">页脚文案</label>
              <input id="footer" v-model="config.footer" type="text" maxlength="100" />
            </div>
            <div class="field">
              <label for="icp">页脚备案号</label>
              <input id="icp" v-model="config.icp" type="text" maxlength="100" placeholder="例如：桂ICP备2026000000号" />
            </div>
            <div class="field">
              <label for="github">GitHub 链接</label>
              <input id="github" v-model="config.github" type="text" placeholder="https://github.com/…" />
            </div>
            <div class="field">
              <label for="email">联系邮箱</label>
              <input id="email" v-model="config.email" type="email" placeholder="you@example.com" />
            </div>
          </div>
        </section>
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">PREVIEW</span><h2>页脚预览</h2></div></div>
            <div class="adm-footer-preview">
              <span>© 2026 {{ config.siteName || '墨记' }} · {{ config.footer || '用文字抵抗遗忘' }}</span>
              <div>
                <span>关于</span>
                <span v-if="config.github">GitHub</span>
                <span v-if="config.email">邮箱</span>
                <span v-if="config.icp">{{ config.icp }}</span>
              </div>
            </div>
          </section>
        </aside>
      </div>

      <div v-else class="adm-settings-grid">
        <section class="adm-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">ADVANCED</span><h2>备案与代码注入</h2></div></div>
          <div class="form-grid">
            <div class="field">
              <label for="policeRecord">公安联网备案号</label>
              <input id="policeRecord" v-model="config.policeRecord" maxlength="100" placeholder="例如：桂公网安备 45000000000000号" />
            </div>
            <div class="field span-2">
              <label for="customHead">自定义 Head 代码</label>
              <textarea id="customHead" v-model="config.customHead" rows="5" placeholder="统计脚本、站点验证或自定义 meta；保存后插入 &lt;head&gt;"></textarea>
              <p class="meta">仅管理员可编辑，会原样插入全站页面头部；请只粘贴可信代码。</p>
            </div>
            <div class="field span-2">
              <label for="customFooter">自定义页脚 HTML</label>
              <textarea id="customFooter" v-model="config.customFooter" rows="4" placeholder="可放备案图标、赞助说明等；会显示在页脚下方"></textarea>
            </div>
          </div>
        </section>
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">SAFETY</span><h2>代码注入说明</h2></div></div>
            <p class="adm-note">自定义 Head/Footer 不会经过 Markdown 渲染，直接进入页面。请不要放入密码、API Key 或未经审查的第三方脚本。</p>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.site-announcement-hint { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--accent-soft); color: var(--ink-soft); font-size: 12px; }

.music-settings-grid { align-items: start; }
.music-source-switch { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: 0 0 16px; padding: 4px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface, var(--accent-soft)); }
.music-source-switch button { display: grid; grid-template-columns: 30px 1fr; grid-template-rows: auto auto; gap: 0 9px; align-items: center; padding: 11px 12px; border: 1px solid transparent; border-radius: 9px; background: transparent; color: var(--ink-soft); text-align: left; cursor: pointer; transition: .16s ease; }
.music-source-switch button:hover { background: var(--paper); }
.music-source-switch button.on { border-color: var(--accent); background: var(--paper); box-shadow: 0 4px 14px rgba(36, 88, 159, .08); }
.music-source-icon { grid-row: span 2; display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px; background: var(--accent-soft); color: var(--accent-ink); font-size: 16px; }
.music-source-switch strong { font-size: 12.5px; color: var(--ink); }
.music-source-switch small { font-size: 10px; color: var(--muted); }
.music-platform-picker { display: flex; flex-wrap: wrap; gap: 8px; }
.music-platform-picker button { display: inline-flex; align-items: center; gap: 7px; padding: 7px 11px; border: 1px solid var(--line); border-radius: 9px; background: var(--paper); color: var(--ink-soft); font-size: 12px; cursor: pointer; }
.music-platform-picker button span { display: grid; place-items: center; width: 19px; height: 19px; border-radius: 6px; background: color-mix(in srgb, var(--platform-tone) 14%, transparent); color: var(--platform-tone); font-size: 10px; font-weight: 800; }
.music-platform-picker button.on { border-color: var(--platform-tone); color: var(--ink); box-shadow: 0 0 0 2px color-mix(in srgb, var(--platform-tone) 12%, transparent); }
.music-playlist-check { display: flex; align-items: center; gap: 11px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface, var(--accent-soft)); }
.music-playlist-check > div { display: grid; gap: 3px; min-width: 0; flex: 1; }
.music-playlist-check strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12.5px; }
.music-playlist-check small { color: var(--muted); font-size: 10.5px; }
.music-platform-badge { display: grid; place-items: center; width: 34px; height: 34px; flex: 0 0 34px; border-radius: 9px; background: color-mix(in srgb, var(--platform-tone) 13%, var(--paper)); color: var(--platform-tone); font-size: 15px; font-weight: 800; }
.music-preview-card { overflow: hidden; }
.music-preview-shell { position: relative; display: flex; align-items: center; gap: 13px; padding: 18px; border: 1px solid var(--line); border-radius: 13px; background: linear-gradient(135deg, var(--accent-soft), var(--paper)); }
.music-preview-shell.is-playlist { background: linear-gradient(135deg, color-mix(in srgb, var(--platform-tone, var(--accent)) 10%, var(--paper)), var(--paper)); }
.music-preview-orb { display: grid; place-items: center; width: 44px; height: 44px; flex: 0 0 44px; border-radius: 13px; background: color-mix(in srgb, var(--platform-tone, var(--accent)) 14%, var(--paper)); color: var(--platform-tone, var(--accent)); font-size: 18px; font-weight: 800; }
.music-preview-copy { display: grid; gap: 4px; min-width: 0; flex: 1; }
.music-preview-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink); font-size: 14px; }
.music-preview-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: 10.5px; }
.music-preview-eq { display: flex; align-items: flex-end; gap: 3px; width: 20px; height: 19px; flex: 0 0 20px; }
.music-preview-eq i { width: 3px; border-radius: 3px; background: var(--accent-ink); animation: music-eq 1s ease-in-out infinite alternate; }
.music-preview-eq i:nth-child(1) { height: 7px; animation-delay: 0s; }
.music-preview-eq i:nth-child(2) { height: 15px; animation-delay: .18s; }
.music-preview-eq i:nth-child(3) { height: 10px; animation-delay: .34s; }
.music-preview-eq i:nth-child(4) { height: 18px; animation-delay: .09s; }
@keyframes music-eq { from { transform: scaleY(.55); opacity: .55; } to { transform: scaleY(1); opacity: 1; } }
.music-tips { display: grid; gap: 9px; margin: 0; padding: 0 0 0 18px; color: var(--ink-soft); font-size: 11.5px; line-height: 1.7; }
.music-tips code { color: var(--accent-ink); font-family: var(--mono); font-size: 10.5px; }
.music-config-card .adm-card-head p { max-width: 620px; }
.music-link-row { display: flex; align-items: center; gap: 9px; }
.music-link-row input { flex: 1; min-width: 0; }
.music-link-row .btn { flex: 0 0 auto; white-space: nowrap; }
.music-parse-panel { display: grid; gap: 9px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: color-mix(in srgb, var(--platform-tone, var(--accent)) 5%, var(--surface, var(--accent-soft))); transition: border-color .18s ease, background .18s ease; }
.music-parse-panel.error { border-color: color-mix(in srgb, #c94a4a 36%, var(--line)); background: color-mix(in srgb, #c94a4a 5%, var(--paper)); }
.music-parse-head { display: flex; align-items: center; gap: 11px; min-width: 0; }
.music-parse-cover { display: grid; place-items: center; width: 44px; height: 44px; flex: 0 0 44px; overflow: hidden; border-radius: 11px; background: color-mix(in srgb, var(--platform-tone, var(--accent)) 14%, var(--paper)); color: var(--platform-tone, var(--accent)); font-size: 18px; font-weight: 800; }
.music-parse-cover img { width: 100%; height: 100%; object-fit: cover; }
.music-parse-copy { display: grid; gap: 3px; min-width: 0; flex: 1; }
.music-parse-copy strong { overflow: hidden; color: var(--ink); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.music-parse-copy small { overflow: hidden; color: var(--muted); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.music-parse-state { flex: 0 0 auto; padding: 4px 8px; border-radius: 999px; background: color-mix(in srgb, var(--muted) 10%, var(--paper)); color: var(--muted); font-size: 9.5px; font-weight: 700; letter-spacing: .04em; }
.music-parse-state.ok { background: color-mix(in srgb, #2f8f5b 10%, var(--paper)); color: #2f8f5b; }
.music-parse-state.failed { background: color-mix(in srgb, #c94a4a 10%, var(--paper)); color: #c94a4a; }
.music-parse-note { margin: 0; color: var(--muted); font-size: 10.5px; line-height: 1.6; }
.music-parse-note.danger { color: #c94a4a; }
.music-parse-tracks { display: grid; gap: 1px; padding-top: 5px; border-top: 1px solid color-mix(in srgb, var(--line) 70%, transparent); }
.music-parse-track { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 7px; padding: 6px 2px; }
.music-parse-index { color: var(--muted); font-size: 9.5px; font-variant-numeric: tabular-nums; }
.music-parse-track > div { display: grid; gap: 2px; min-width: 0; }
.music-parse-track strong { overflow: hidden; color: var(--ink-soft); font-size: 11px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.music-parse-track small { overflow: hidden; color: var(--muted); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }
.music-parse-track em { padding: 2px 6px; border-radius: 999px; background: color-mix(in srgb, #2f8f5b 9%, var(--paper)); color: #2f8f5b; font-size: 9px; font-style: normal; white-space: nowrap; }
.music-parse-track em.blocked { background: color-mix(in srgb, #b07a1e 9%, var(--paper)); color: #9a6810; }
.music-parse-more { margin: 2px 0 0; color: var(--muted); font-size: 9.5px; }
.music-parse-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 1px; }
.music-parse-actions:empty { display: none; }
.music-preview-cover { display: grid; place-items: center; width: 44px; height: 44px; flex: 0 0 44px; overflow: hidden; border-radius: 13px; background: color-mix(in srgb, var(--platform-tone, var(--accent)) 14%, var(--paper)); color: var(--platform-tone, var(--accent)); font-size: 18px; font-weight: 800; }
.music-preview-cover img { width: 100%; height: 100%; object-fit: cover; }
[data-theme="dark"] .music-parse-panel { background: rgba(255, 255, 255, .035); }
[data-theme="dark"] .music-parse-panel.error { background: rgba(201, 74, 74, .08); }
@media (max-width: 720px) { .music-link-row { flex-wrap: wrap; } .music-link-row input { flex-basis: 100%; } }

.music-source-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.music-source-actions .meta { flex: 1; min-width: 220px; }
.music-source-panel { display: grid; gap: 12px; padding: 15px; border: 1px solid var(--line); border-radius: 13px; background: color-mix(in srgb, var(--accent) 5%, var(--paper)); transition: border-color .18s ease, background .18s ease; }
.music-source-panel.loading { border-color: color-mix(in srgb, var(--accent) 38%, var(--line)); }
.music-source-panel.error { border-color: color-mix(in srgb, #c94a4a 38%, var(--line)); background: color-mix(in srgb, #c94a4a 5%, var(--paper)); }
.music-source-panel.ready { border-color: color-mix(in srgb, #2f8f5b 30%, var(--line)); }
.music-source-head { display: flex; align-items: center; gap: 11px; min-width: 0; }
.music-source-orb { display: grid; place-items: center; width: 44px; height: 44px; flex: 0 0 44px; border-radius: 12px; background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 22%, var(--paper)), color-mix(in srgb, var(--accent) 7%, var(--paper))); color: var(--accent-ink); font-size: 18px; font-weight: 800; }
.music-source-copy { display: grid; gap: 3px; min-width: 0; flex: 1; }
.music-source-copy strong { overflow: hidden; color: var(--ink); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.music-source-copy small { overflow: hidden; color: var(--muted); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.music-source-state { flex: 0 0 auto; padding: 4px 9px; border-radius: 999px; background: color-mix(in srgb, var(--muted) 10%, var(--paper)); color: var(--muted); font-size: 9.5px; font-weight: 700; letter-spacing: .03em; }
.music-source-state.ok { background: color-mix(in srgb, #2f8f5b 10%, var(--paper)); color: #2f8f5b; }
.music-source-state.failed { background: color-mix(in srgb, #c94a4a 10%, var(--paper)); color: #c94a4a; }
.music-source-note { margin: 0; color: var(--muted); font-size: 10.5px; line-height: 1.6; }
.music-source-note.danger { color: #c94a4a; }
.music-source-meta { display: grid; grid-template-columns: .7fr 1.3fr 1.5fr; gap: 8px; padding: 11px 12px; border: 1px solid color-mix(in srgb, var(--line) 72%, transparent); border-radius: 10px; background: color-mix(in srgb, var(--paper) 78%, transparent); }
.music-source-meta > div { display: grid; gap: 3px; min-width: 0; }
.music-source-meta span { color: var(--muted); font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }
.music-source-meta strong { overflow: hidden; color: var(--ink-soft); font-size: 11px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.music-source-platforms { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.music-source-platforms > span { color: var(--muted); font-size: 10px; }
.music-source-platforms > div { display: flex; flex-wrap: wrap; gap: 6px; }
.music-platform-chip { padding: 4px 9px; border-radius: 999px; background: color-mix(in srgb, var(--accent) 8%, var(--paper)); color: var(--accent-ink); font-size: 10px; font-weight: 600; }
.music-source-test { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid color-mix(in srgb, #2f8f5b 22%, var(--line)); border-radius: 10px; background: color-mix(in srgb, #2f8f5b 7%, var(--paper)); }
.music-source-test-eq { display: flex; align-items: flex-end; gap: 3px; width: 20px; height: 18px; }
.music-source-test-eq i { width: 3px; border-radius: 3px; background: #2f8f5b; animation: music-eq 1s ease-in-out infinite alternate; }
.music-source-test-eq i:nth-child(1) { height: 8px; }
.music-source-test-eq i:nth-child(2) { height: 15px; animation-delay: .2s; }
.music-source-test-eq i:nth-child(3) { height: 11px; animation-delay: .35s; }
.music-source-test > div { display: grid; gap: 2px; min-width: 0; flex: 1; }
.music-source-test strong { overflow: hidden; color: var(--ink); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.music-source-test small { color: var(--muted); font-size: 9.5px; }
.music-source-test > span:last-child { color: #2f8f5b; font-size: 9px; font-weight: 800; letter-spacing: .08em; }
.music-app-preview { display: grid; gap: 13px; padding: 16px; border: 1px solid var(--line); border-radius: 15px; background: linear-gradient(155deg, color-mix(in srgb, var(--accent) 12%, var(--paper)), var(--paper) 58%, color-mix(in srgb, #9b6bff 8%, var(--paper))); box-shadow: 0 14px 30px rgba(17, 24, 39, .08); }
.music-app-preview-head { display: flex; align-items: center; gap: 11px; }
.music-app-preview-cover { display: grid; place-items: center; width: 48px; height: 48px; flex: 0 0 48px; border-radius: 14px; background: linear-gradient(135deg, var(--accent), #8d6bff); color: #fff; font-size: 20px; box-shadow: 0 8px 18px color-mix(in srgb, var(--accent) 26%, transparent); }
.music-app-preview-head > div { display: grid; gap: 3px; min-width: 0; flex: 1; }
.music-app-preview-head strong { color: var(--ink); font-size: 14px; }
.music-app-preview-head small { color: var(--muted); font-size: 10px; }
.music-app-preview-eq { display: flex; align-items: flex-end; gap: 3px; width: 20px; height: 18px; }
.music-app-preview-eq i { width: 3px; border-radius: 3px; background: var(--accent); animation: music-eq 1s ease-in-out infinite alternate; }
.music-app-preview-eq i:nth-child(1) { height: 7px; }
.music-app-preview-eq i:nth-child(2) { height: 14px; animation-delay: .16s; }
.music-app-preview-eq i:nth-child(3) { height: 10px; animation-delay: .3s; }
.music-app-preview-eq i:nth-child(4) { height: 17px; animation-delay: .08s; }
.music-app-preview-search { padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; background: color-mix(in srgb, var(--paper) 75%, transparent); color: var(--muted); font-size: 11px; }
.music-app-preview-controls { display: flex; align-items: center; justify-content: space-between; color: var(--ink-soft); font-size: 10px; }
.music-app-preview-controls strong { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 14px; box-shadow: 0 7px 16px color-mix(in srgb, var(--accent) 25%, transparent); }
.music-app-preview-bar { height: 4px; overflow: hidden; border-radius: 999px; background: color-mix(in srgb, var(--muted) 14%, var(--paper)); }
.music-app-preview-bar i { display: block; width: 42%; height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--accent), #8d6bff); }
@media (max-width: 720px) { .music-source-meta { grid-template-columns: 1fr; } }
</style>
