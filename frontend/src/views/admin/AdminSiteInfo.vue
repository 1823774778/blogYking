<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../api'
import { state, toast } from '../../state'
import { MUSIC_PLATFORMS, detectMusicPlatform, platformMeta, playlistEmbedUrl, playlistOpenUrl } from '../../utils/music'

const route = useRoute()
const activeTab = computed(() => String(route.query.tab || 'basic'))
const config = ref({})
const loading = ref(false)
const saving = ref(false)
const uploadingLogo = ref(false)
const uploadingAvatar = ref(false)
const uploadingMusic = ref(false)
const logoInput = ref(null)
const avatarInput = ref(null)
const musicInput = ref(null)
const logoFailed = ref(false)
const avatarFailed = ref(false)
const showLogoPreview = computed(() => Boolean(config.value.logo) && !logoFailed.value)
const showAvatarPreview = computed(() => Boolean(config.value.avatar) && !avatarFailed.value)
const previewFallback = computed(() => (config.value.siteName || '墨').trim().slice(0, 1) || '墨')

const musicSourceValue = computed(() => (config.value.musicSource === 'playlist' ? 'playlist' : 'audio'))
const musicPlatform = computed(() => platformMeta(config.value.musicPlaylistPlatform || detectMusicPlatform(config.value.musicPlaylistUrl)))
const musicPreviewTitle = computed(() => musicSourceValue.value === 'playlist'
  ? (config.value.musicPlaylistTitle || config.value.musicTitle || '未选择歌单')
  : (config.value.musicTitle || '未选择音乐'))
const musicPreviewSubtitle = computed(() => musicSourceValue.value === 'playlist'
  ? `${musicPlatform.value.label} · 分享歌单`
  : (config.value.musicArtist || '单曲音频'))
const musicEmbedAvailable = computed(() => Boolean(playlistEmbedUrl(config.value.musicPlaylistPlatform, config.value.musicPlaylistUrl)))

watch(() => config.value.logo, () => { logoFailed.value = false })
watch(() => config.value.avatar, () => { avatarFailed.value = false })

function boolValue(value) {
  return String(value).toLowerCase() === 'true'
}

function setBool(key, event) {
  config.value[key] = event.target.checked ? 'true' : 'false'
}

function setMusicSource(source) {
  config.value.musicSource = source
}

function onPlaylistInput() {
  const platform = detectMusicPlatform(config.value.musicPlaylistUrl)
  config.value.musicPlaylistPlatform = platform
}

function playlistOpenLink() {
  return playlistOpenUrl(config.value.musicPlaylistPlatform, config.value.musicPlaylistUrl)
}

async function load() {
  loading.value = true
  try {
    config.value = (await http.get('/admin/config')).data
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
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

function pickMusic() {
  musicInput.value?.click()
}

async function uploadMusic(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  uploadingMusic.value = true
  try {
    const res = await http.post('/upload/audio', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    config.value.musicUrl = res.data.url
    if (!config.value.musicTitle) config.value.musicTitle = file.name.replace(/\.[^.]+$/, '')
    toast('音乐已上传，保存后生效')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    uploadingMusic.value = false
    event.target.value = ''
  }
}

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
            <div class="field span-2">
              <label class="adm-switch-label">
                <span>评论先审后发</span>
                <input type="checkbox" :checked="boolValue(config.commentReview)" @change="setBool('commentReview', $event)" />
                <i class="adm-switch" aria-hidden="true"></i>
              </label>
              <p class="adm-note">开启后新评论进入待审核状态，管理员通过后才在前台展示。已有评论不受影响。</p>
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
        <section class="adm-card">
          <div class="adm-card-head">
            <div><span class="adm-card-kicker">MUSIC</span><h2>站点音乐</h2><p>单曲音频或各大平台分享歌单，均可展示在前台右下角的悬浮播放器。</p></div>
          </div>

          <div class="music-source-switch" role="tablist" aria-label="音乐来源">
            <button type="button" :class="{ on: musicSourceValue === 'audio' }" @click="setMusicSource('audio')">
              <span class="music-source-icon">♪</span>
              <strong>单曲音频</strong>
              <small>上传或填写直链</small>
            </button>
            <button type="button" :class="{ on: musicSourceValue === 'playlist' }" @click="setMusicSource('playlist')">
              <span class="music-source-icon">♫</span>
              <strong>平台歌单</strong>
              <small>网易云 / QQ 音乐等</small>
            </button>
          </div>

          <div class="form-grid">
            <div class="field span-2">
              <label class="adm-switch-label">
                <span>启用站点音乐</span>
                <input type="checkbox" :checked="boolValue(config.musicEnabled)" @change="setBool('musicEnabled', $event)" />
                <i class="adm-switch" aria-hidden="true"></i>
              </label>
              <p class="adm-note">浏览器禁止自动播放，读者点击右下角音乐按钮后才会展开并开始播放，不会强制打扰浏览。</p>
            </div>

            <template v-if="musicSourceValue === 'audio'">
              <div class="field">
                <label for="musicTitle">音乐名称</label>
                <input id="musicTitle" v-model="config.musicTitle" type="text" maxlength="64" placeholder="例如：夜航星" />
              </div>
              <div class="field">
                <label for="musicArtist">歌手 / 来源</label>
                <input id="musicArtist" v-model="config.musicArtist" type="text" maxlength="64" placeholder="例如：不才 / 本站音频" />
              </div>
              <div class="field span-2">
                <label for="musicUrl">音乐地址</label>
                <input id="musicUrl" v-model="config.musicUrl" type="text" placeholder="https://… 或上传音频" />
              </div>
              <div class="field span-2">
                <label>上传音频</label>
                <div class="adm-media-row">
                  <audio v-if="config.musicUrl" :src="config.musicUrl" controls preload="metadata" class="adm-audio-preview"></audio>
                  <div>
                    <button class="btn btn-ghost btn-sm" type="button" :disabled="uploadingMusic" @click="pickMusic">{{ uploadingMusic ? '上传中…' : '选择音频' }}</button>
                    <p class="meta">支持 mp3 / ogg / wav / m4a / aac / flac，30MB 以内。</p>
                    <input ref="musicInput" type="file" accept="audio/*" hidden @change="uploadMusic" />
                  </div>
                </div>
              </div>
            </template>

            <template v-else>
              <div class="field span-2">
                <label>分享平台</label>
                <div class="music-platform-picker">
                  <button
                    v-for="platform in MUSIC_PLATFORMS"
                    :key="platform.value"
                    type="button"
                    :class="{ on: config.musicPlaylistPlatform === platform.value }"
                    :style="{ '--platform-tone': platform.tone }"
                    @click="config.musicPlaylistPlatform = platform.value"
                  >
                    <span>{{ platform.mark }}</span>{{ platform.label }}
                  </button>
                </div>
              </div>
              <div class="field span-2">
                <label for="musicPlaylistUrl">歌单分享链接</label>
                <input
                  id="musicPlaylistUrl"
                  v-model="config.musicPlaylistUrl"
                  type="text"
                  placeholder="粘贴网易云、QQ 音乐、酷狗或酷我的歌单分享链接"
                  @input="onPlaylistInput"
                />
                <p class="meta">自动识别平台；网易云与 QQ 音乐支持内嵌播放，其他平台会在前台提供“打开歌单”入口。</p>
              </div>
              <div class="field span-2">
                <label for="musicPlaylistTitle">歌单名称</label>
                <input id="musicPlaylistTitle" v-model="config.musicPlaylistTitle" type="text" maxlength="80" placeholder="例如：深夜写代码时听的歌" />
              </div>
              <div class="field span-2">
                <label>歌单预览</label>
                <div class="music-playlist-check">
                  <span class="music-platform-badge" :style="{ '--platform-tone': musicPlatform.tone }">{{ musicPlatform.mark }}</span>
                  <div>
                    <strong>{{ config.musicPlaylistTitle || '未命名歌单' }}</strong>
                    <small>{{ musicPlatform.label }} · {{ musicEmbedAvailable ? '支持内嵌播放' : '将跳转到原平台播放' }}</small>
                  </div>
                  <a v-if="playlistOpenLink()" :href="playlistOpenLink()" target="_blank" rel="noopener" class="btn-text">打开歌单 ↗</a>
                </div>
              </div>
            </template>
          </div>
        </section>

        <aside class="adm-side-stack">
          <section class="adm-card music-preview-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">PLAYER</span><h2>播放器预览</h2></div></div>
            <div class="music-preview-shell" :class="{ 'is-playlist': musicSourceValue === 'playlist' }" :style="{ '--platform-tone': musicPlatform.tone }">
              <span class="music-preview-orb" :style="{ '--platform-tone': musicPlatform.tone }">
                {{ musicSourceValue === 'playlist' ? musicPlatform.mark : '♪' }}
              </span>
              <div class="music-preview-copy">
                <strong>{{ musicPreviewTitle }}</strong>
                <span>{{ musicPreviewSubtitle }}</span>
              </div>
              <span class="music-preview-eq" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
            </div>
            <p class="adm-note">{{ boolValue(config.musicEnabled) ? '已启用，保存后显示在前台右下角。' : '尚未启用，保存并打开开关后才会显示。' }}</p>
          </section>
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">TIPS</span><h2>歌单使用说明</h2></div></div>
            <ul class="music-tips">
              <li>在音乐平台网页版打开歌单，点击“分享”复制链接。</li>
              <li>网易云链接形如 <code>music.163.com/playlist?id=…</code>。</li>
              <li>QQ 音乐链接形如 <code>y.qq.com/n/ryqq/playlist/…</code>。</li>
              <li>平台限制内嵌时，读者仍可通过“打开歌单”跳转收听。</li>
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
@media (max-width: 720px) { .music-source-switch { grid-template-columns: 1fr; } .music-platform-picker { display: grid; grid-template-columns: 1fr 1fr; } .music-playlist-check { flex-wrap: wrap; } }
</style>
