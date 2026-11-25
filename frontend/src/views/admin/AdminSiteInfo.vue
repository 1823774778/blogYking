<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { state, toast } from '../../state'
import { detectMusicPlatform, platformMeta, playlistOpenUrl } from '../../utils/music'

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
const previewLoading = ref(false)
const previewData = ref(null)
const previewError = ref('')
let previewTimer = null
let previewSeq = 0
const showLogoPreview = computed(() => Boolean(config.value.logo) && !logoFailed.value)
const showAvatarPreview = computed(() => Boolean(config.value.avatar) && !avatarFailed.value)
const previewFallback = computed(() => (config.value.siteName || '墨').trim().slice(0, 1) || '墨')

const musicLink = computed({
  get: () => config.value.musicUrl || config.value.musicPlaylistUrl || '',
  set: (value) => {
    config.value.musicUrl = value
    config.value.musicPlaylistUrl = value
  }
})
const musicPlatform = computed(() => platformMeta(
  previewData.value?.platform || config.value.musicPlaylistPlatform || detectMusicPlatform(musicLink.value)
))
const musicPreviewTitle = computed(() => previewData.value?.title || config.value.musicPlaylistTitle || config.value.musicTitle || '未选择歌单')
const musicPreviewCover = computed(() => previewData.value?.cover || '')
const musicPreviewSubtitle = computed(() => {
  if (!musicLink.value.trim()) return '粘贴分享链接后自动读取'
  if (previewLoading.value) return '正在云端读取歌单…'
  if (previewError.value) return '读取失败，请检查链接'
  if (previewData.value) {
    const loaded = Number(previewData.value.loadedCount || 0)
    const playable = Number(previewData.value.playableCount || 0)
    return `${musicPlatform.value.label} · ${loaded} 首${playable ? ` · ${playable} 首可在线播放` : ' · 原平台收听'}`
  }
  return `${musicPlatform.value.label} · 等待读取`
})
const previewTracks = computed(() => (previewData.value?.tracks || []).slice(0, 5))
const previewOpenLink = computed(() => playlistOpenUrl(musicPlatform.value.value, musicLink.value))
const previewStateText = computed(() => {
  if (previewLoading.value) return '正在读取云端歌单…'
  if (previewError.value) return previewError.value
  if (!musicLink.value.trim()) return '粘贴一个歌单分享链接，保存前可先预览读取结果。'
  if (previewData.value) return previewData.value.message || '已读取歌单，保存后前台即可播放。'
  return '等待云端读取'
})

watch(() => config.value.logo, () => { logoFailed.value = false })
watch(() => config.value.avatar, () => { avatarFailed.value = false })
watch(musicLink, () => schedulePreview())

function boolValue(value) {
  return String(value).toLowerCase() === 'true'
}

function setBool(key, event) {
  config.value[key] = event.target.checked ? 'true' : 'false'
}

function schedulePreview() {
  if (previewTimer) clearTimeout(previewTimer)
  const link = musicLink.value.trim()
  if (!link) {
    previewSeq += 1
    previewLoading.value = false
    previewData.value = null
    previewError.value = ''
    return
  }
  previewTimer = setTimeout(refreshPreview, 700)
}

async function refreshPreview() {
  const link = musicLink.value.trim()
  if (!link) return
  const seq = ++previewSeq
  previewLoading.value = true
  previewError.value = ''
  try {
    const res = await http.get('/admin/music/preview', {
      params: { url: link },
      timeout: 30000
    })
    if (seq !== previewSeq) return
    previewData.value = res.data || null
    if (res.data?.title) config.value.musicPlaylistTitle = res.data.title
    if (res.data?.platform) config.value.musicPlaylistPlatform = res.data.platform
  } catch (e) {
    if (seq !== previewSeq) return
    previewData.value = null
    previewError.value = e.message || '云端歌单读取失败'
  } finally {
    if (seq === previewSeq) previewLoading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const data = (await http.get('/admin/config')).data || {}
    const link = data.musicSource === 'audio'
      ? (data.musicUrl || data.musicPlaylistUrl || '')
      : (data.musicPlaylistUrl || data.musicUrl || '')
    data.musicUrl = link
    data.musicPlaylistUrl = link
    data.musicSource = 'playlist'
    config.value = data
    if (link) schedulePreview()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const link = musicLink.value.trim()
    config.value.musicUrl = link
    config.value.musicPlaylistUrl = link
    config.value.musicSource = 'playlist'
    config.value.musicPlaylistPlatform = musicPlatform.value.value
    if (previewData.value?.title) config.value.musicPlaylistTitle = previewData.value.title
    const res = await http.put('/admin/config', config.value)
    config.value = res.data
    try {
      const meta = await http.get('/meta/site')
      state.site = { ...state.site, ...meta.data }
    } catch (e) {
      /* 前台元信息刷新失败不阻塞保存结果 */
    }
    toast('站点信息已保存，前台立即生效')
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
  if (previewTimer) clearTimeout(previewTimer)
  previewSeq += 1
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
              <span class="adm-card-kicker">MUSIC</span>
              <h2>站点音乐</h2>
              <p>只需一个公开歌单分享链接。网易云歌单会由本站云端解析为可点击播放器，其他平台自动使用官方播放器或原站入口。</p>
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
              <label for="musicLink">歌单 / 音频分享链接</label>
              <div class="music-link-row">
                <input
                  id="musicLink"
                  v-model="musicLink"
                  type="text"
                  placeholder="粘贴网易云、QQ 音乐、酷狗、酷我歌单分享链接，或 mp3 / m4a 音频直链"
                  spellcheck="false"
                />
                <button class="btn btn-ghost btn-sm" type="button" :disabled="previewLoading || !musicLink.trim()" @click="refreshPreview">
                  {{ previewLoading ? '读取中…' : '立即读取' }}
                </button>
              </div>
              <p class="meta">一个链接即可。网易云歌单可站内切歌；受版权限制的曲目会标记，并可跳转原平台收听。</p>
            </div>

            <div class="field span-2">
              <label>云端读取结果</label>
              <div class="music-parse-panel" :class="{ loading: previewLoading, error: previewError }" :style="{ '--platform-tone': musicPlatform.tone }">
                <div class="music-parse-head">
                  <span class="music-parse-cover">
                    <img v-if="musicPreviewCover" :src="musicPreviewCover" alt="" referrerpolicy="no-referrer" />
                    <template v-else>{{ musicPlatform.mark }}</template>
                  </span>
                  <div class="music-parse-copy">
                    <strong>{{ musicPreviewTitle }}</strong>
                    <small>{{ musicPreviewSubtitle }}</small>
                  </div>
                  <span class="music-parse-state" :class="{ ok: previewData && !previewError, failed: previewError }">
                    {{ previewLoading ? '读取中' : previewError ? '读取失败' : previewData ? '已读取' : '待读取' }}
                  </span>
                </div>

                <p class="music-parse-note" :class="{ danger: previewError }">{{ previewStateText }}</p>

                <div v-if="previewTracks.length" class="music-parse-tracks">
                  <div v-for="(track, index) in previewTracks" :key="track.id + '-' + index" class="music-parse-track">
                    <span class="music-parse-index">{{ String(index + 1).padStart(2, '0') }}</span>
                    <div>
                      <strong>{{ track.title }}</strong>
                      <small>{{ track.artist || '未知歌手' }}<template v-if="track.album"> · {{ track.album }}</template></small>
                    </div>
                    <em :class="{ blocked: !track.playable }">{{ track.playable ? '可播放' : '版权限制' }}</em>
                  </div>
                  <p v-if="previewData?.loadedCount > previewTracks.length" class="music-parse-more">
                    还有 {{ previewData.loadedCount - previewTracks.length }} 首，保存后在前台完整展示
                  </p>
                </div>

                <div class="music-parse-actions">
                  <button v-if="previewError" class="btn btn-ghost btn-sm" type="button" @click="refreshPreview">重新读取</button>
                  <a v-if="previewOpenLink" :href="previewOpenLink" target="_blank" rel="noopener" class="btn-text">打开原歌单 ↗</a>
                </div>
              </div>
            </div>
          </div>
        </section>

        <aside class="adm-side-stack">
          <section class="adm-card music-preview-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">PLAYER</span><h2>前台播放器预览</h2></div></div>
            <div class="music-preview-shell is-playlist" :style="{ '--platform-tone': musicPlatform.tone }">
              <span class="music-preview-cover">
                <img v-if="musicPreviewCover" :src="musicPreviewCover" alt="" referrerpolicy="no-referrer" />
                <template v-else>{{ musicPlatform.mark }}</template>
              </span>
              <div class="music-preview-copy">
                <strong>{{ musicPreviewTitle }}</strong>
                <span>{{ musicPreviewSubtitle }}</span>
              </div>
              <span class="music-preview-eq" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
            </div>
            <p class="adm-note">{{ boolValue(config.musicEnabled) ? '已启用。保存后读者可在前台点击播放、切歌和收起面板。' : '尚未启用，保存并打开开关后才会显示。' }}</p>
          </section>

          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">TIPS</span><h2>链接使用说明</h2></div></div>
            <ul class="music-tips">
              <li>只需粘贴一个歌单或音频分享链接，不用再选择平台。</li>
              <li>网易云音乐由本站读取曲目，可在前台直接播放、暂停、切歌。</li>
              <li>QQ 音乐、酷狗、酷我使用官方播放器或“打开歌单”入口。</li>
              <li>版权受限曲目会显示标记，读者仍可前往原平台收听。</li>
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
</style>
