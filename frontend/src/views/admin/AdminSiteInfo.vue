<script setup>
import { computed, onMounted, ref, watch } from 'vue'
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
const uploadingMusic = ref(false)
const logoInput = ref(null)
const avatarInput = ref(null)
const musicInput = ref(null)
const logoFailed = ref(false)
const avatarFailed = ref(false)
const showLogoPreview = computed(() => Boolean(config.value.logo) && !logoFailed.value)
const showAvatarPreview = computed(() => Boolean(config.value.avatar) && !avatarFailed.value)
const previewFallback = computed(() => (config.value.siteName || '墨').trim().slice(0, 1) || '墨')

watch(() => config.value.logo, () => { logoFailed.value = false })
watch(() => config.value.avatar, () => { avatarFailed.value = false })

function boolValue(value) {
  return String(value).toLowerCase() === 'true'
}

function setBool(key, event) {
  config.value[key] = event.target.checked ? 'true' : 'false'
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

      <div v-else-if="activeTab === 'music'" class="adm-settings-grid">
        <section class="adm-card">
          <div class="adm-card-head"><div><span class="adm-card-kicker">MUSIC</span><h2>站点音乐</h2></div></div>
          <div class="form-grid">
            <div class="field span-2">
              <label class="adm-switch-label">
                <span>启用站点音乐</span>
                <input type="checkbox" :checked="boolValue(config.musicEnabled)" @change="setBool('musicEnabled', $event)" />
                <i class="adm-switch" aria-hidden="true"></i>
              </label>
              <p class="adm-note">浏览器禁止自动播放，读者点击右下角音乐按钮后开始播放，不会强制打扰浏览。</p>
            </div>
            <div class="field">
              <label for="musicTitle">音乐名称</label>
              <input id="musicTitle" v-model="config.musicTitle" type="text" maxlength="64" placeholder="例如：夜航星" />
            </div>
            <div class="field">
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
          </div>
        </section>
        <aside class="adm-side-stack">
          <section class="adm-card">
            <div class="adm-card-head"><div><span class="adm-card-kicker">PLAYER</span><h2>播放器预览</h2></div></div>
            <div class="adm-music-preview">
              <span class="adm-music-bars"><i></i><i></i><i></i><i></i></span>
              <div>
                <strong>{{ config.musicTitle || '未选择音乐' }}</strong>
                <span>{{ boolValue(config.musicEnabled) ? '已启用，显示在前台右下角' : '未启用' }}</span>
              </div>
            </div>
            <p class="adm-note">保存后到前台首页或文章页右下角查看实际播放按钮。</p>
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
</style>
