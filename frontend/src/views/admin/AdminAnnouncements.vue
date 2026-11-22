<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import http from '../../api'
import { toast } from '../../state'
import MarkdownBody from '../../components/MarkdownBody.vue'

const form = reactive({ title: '站点公告', content: '', link: '', enabled: false })
const config = ref({})
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const aiOpen = ref(false)
const aiLoading = ref(false)
const aiPrompt = ref('')
const aiIdea = ref('')
const aiTone = ref('natural')
const fileInput = ref(null)
const chars = computed(() => form.content.replace(/\s/g, '').length)
const aiAvailable = computed(() => String(config.value.aiEnabled).toLowerCase() === 'true'
  && String(config.value.aiAnnouncementEnabled ?? 'true').toLowerCase() === 'true')

async function load() {
  loading.value = true
  try {
    const [annRes, cfgRes] = await Promise.all([
      http.get('/admin/announcements'),
      http.get('/admin/config')
    ])
    const data = annRes.data || {}
    form.title = data.title || '站点公告'
    form.content = data.content || ''
    form.link = data.link || ''
    form.enabled = String(data.enabled).toLowerCase() === 'true'
    config.value = cfgRes.data || {}
    aiPrompt.value = config.value.aiAnnouncementPrompt || ''
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.content.trim()) {
    toast('公告内容不能为空', 'error')
    return
  }
  saving.value = true
  try {
    const res = await http.put('/admin/announcements', {
      title: form.title.trim(),
      content: form.content,
      link: form.link.trim(),
      enabled: form.enabled
    })
    form.title = res.data.title
    form.content = res.data.content
    form.link = res.data.link
    form.enabled = String(res.data.enabled).toLowerCase() === 'true'
    toast(form.enabled ? '公告已保存并上线' : '公告已保存，当前未展示')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

function openAi() {
  if (!aiAvailable.value) {
    toast('请先在「站点设置 → AI 设置」开启 AI 与 AI 公告', 'error')
    return
  }
  if (!aiIdea.value.trim()) aiIdea.value = form.content.trim()
  aiOpen.value = true
}

async function generate() {
  if (!aiIdea.value.trim()) {
    toast('请先写下公告要点', 'error')
    return
  }
  aiLoading.value = true
  try {
    const res = await http.post('/admin/ai/generate-announcement', {
      idea: aiIdea.value.trim(),
      tone: aiTone.value,
      prompt: aiPrompt.value.trim()
    }, { timeout: 90000 })
    const content = String(res.data?.content || '').trim()
    if (!content) throw new Error('AI 没有返回公告内容')
    form.content = content
    if (!form.title.trim() || form.title === '站点公告') {
      form.title = aiIdea.value.trim().slice(0, 20) || '站点公告'
    }
    aiOpen.value = false
    toast('AI 公告已生成，确认后再保存上线')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    aiLoading.value = false
  }
}

function pickImage() {
  fileInput.value?.click()
}

async function uploadImage(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  uploading.value = true
  try {
    const res = await http.post('/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
    const url = res.data?.url
    if (!url) throw new Error('上传未返回地址')
    form.content = `${form.content}${form.content.endsWith('\n') || !form.content ? '' : '\n\n'}![公告图片](${url})`
    toast('图片已插入公告末尾')
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="adm-page">
    <div class="adm-page-toolbar">
      <span class="adm-total-pill">{{ form.enabled ? '公告正在前台展示' : '公告当前未展示' }} · {{ chars }} 字</span>
      <div class="adm-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickImage">{{ uploading ? '上传中…' : '插入图片' }}</button>
        <button class="btn btn-ghost btn-sm ai-btn" type="button" @click="openAi">✦ AI 生成公告</button>
        <button class="btn btn-sm" type="button" :disabled="saving || loading" @click="save">{{ saving ? '保存中…' : '保存公告' }}</button>
      </div>
    </div>

    <div v-if="loading" class="adm-loading">正在读取公告…</div>

    <template v-else>
      <section class="ann-bar">
        <label class="adm-check-line ann-toggle">
          <input v-model="form.enabled" type="checkbox" />
          <span><strong>在前台展示这条公告</strong><small>关闭后内容仍会保留，首页不显示</small></span>
        </label>
        <div class="ann-ai-state" :class="{ ok: aiAvailable }">
          <span>{{ aiAvailable ? 'AI 公告可用' : 'AI 公告未开启' }}</span>
          <router-link to="/admin/settings?tab=ai">去 AI 设置</router-link>
        </div>
      </section>

      <div class="ann-meta-grid">
        <div class="field">
          <label for="ann-title">公告标题</label>
          <input id="ann-title" v-model="form.title" maxlength="64" placeholder="例如：本站开启评论审核" />
        </div>
        <div class="field">
          <label for="ann-link">跳转链接 <span class="meta">可选</span></label>
          <input id="ann-link" v-model="form.link" maxlength="512" placeholder="https://… 或 /post/12" />
        </div>
      </div>

      <section class="ann-workbench">
        <div class="ann-pane">
          <div class="ann-pane-head"><span>编辑</span><span class="meta">支持 Markdown 与图片</span></div>
          <textarea v-model="form.content" class="ann-textarea" maxlength="2000" placeholder="写一条简短、清楚的公告……"></textarea>
        </div>
        <div class="ann-pane ann-preview-pane">
          <div class="ann-pane-head"><span>预览</span><span class="meta">前台展示效果</span></div>
          <div class="ann-preview">
            <div v-if="form.content.trim()" class="ann-preview-card">
              <div class="ann-preview-head">
                <span class="ann-dot"></span>
                <span>{{ form.title || '站点公告' }}</span>
                <span v-if="form.link" class="ann-arrow">→</span>
              </div>
              <MarkdownBody :content="form.content" />
            </div>
            <div v-else class="adm-empty">左侧输入内容后，这里会显示预览</div>
          </div>
        </div>
      </section>

      <p class="adm-note">公告建议控制在 80–200 字；开启后会在首页头部展示。AI 只负责起草，发布前请人工确认日期、活动和链接是否准确。</p>
    </template>

    <input ref="fileInput" type="file" accept="image/*" hidden @change="uploadImage" />

    <div v-if="aiOpen" class="adm-modal-wrap" @click.self="!aiLoading && (aiOpen = false)">
      <div class="adm-modal ann-ai-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI ANNOUNCEMENT</span><h3>AI 生成公告</h3></div>
          <button class="adm-icon-btn" type="button" :disabled="aiLoading" @click="aiOpen = false">×</button>
        </div>
        <div class="adm-modal-body">
          <div class="field">
            <label>公告要点</label>
            <textarea v-model="aiIdea" rows="4" placeholder="例如：本周六晚 22:00–23:00 迁移服务器，期间可能短暂无法访问；评论审核会延迟。"></textarea>
          </div>
          <div class="field">
            <label>语气</label>
            <select v-model="aiTone">
              <option value="natural">自然、友好</option>
              <option value="brief">极简，一两句话</option>
              <option value="serious">正式、克制</option>
              <option value="festival">轻松、有节日气氛</option>
            </select>
          </div>
          <div class="field">
            <label>本次提示词 <span class="meta">可编辑，仅本次生效</span></label>
            <textarea v-model="aiPrompt" rows="3" placeholder="留空使用站点设置中的默认提示词"></textarea>
          </div>
          <div class="ann-tip">生成结果会覆盖左侧公告正文；保存并勾选“前台展示”后才真正上线。</div>
        </div>
        <div class="adm-modal-foot">
          <button class="btn btn-ghost btn-sm" type="button" :disabled="aiLoading" @click="aiOpen = false">取消</button>
          <button class="btn btn-sm" type="button" :disabled="aiLoading" @click="generate">{{ aiLoading ? '生成中…' : '生成公告' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-btn { color: #1d4ed8; border-color: #bfdbfe; background: #eff6ff; }
.ann-bar { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 14px 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--paper); }
.ann-toggle { margin: 0; }
.ann-toggle span { display: grid; gap: 3px; }
.ann-toggle small { color: var(--muted); font-weight: 400; }
.ann-ai-state { display: flex; align-items: center; gap: 10px; font-size: 12px; color: #b45309; white-space: nowrap; }
.ann-ai-state.ok { color: #047857; }
.ann-ai-state a { color: var(--accent-ink); }
.ann-meta-grid { display: grid; grid-template-columns: 1.2fr 1fr; gap: 14px; margin-top: 16px; }
.ann-workbench { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); min-height: 430px; margin-top: 16px; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; background: var(--paper); }
.ann-pane { display: flex; flex-direction: column; min-width: 0; }
.ann-pane + .ann-pane { border-left: 1px solid var(--line); }
.ann-pane-head { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--line); background: var(--surface, var(--accent-soft)); color: var(--ink-soft); font-size: 12px; }
.ann-pane-head span:first-child { font-weight: 700; letter-spacing: .04em; }
.ann-textarea { flex: 1; min-height: 380px; padding: 18px; border: 0; outline: 0; resize: none; background: var(--paper); color: var(--ink); font: 14px/1.85 ui-monospace, SFMono-Regular, Menlo, monospace; }
.ann-preview-pane { background: var(--surface, transparent); }
.ann-preview { flex: 1; padding: 22px; overflow: auto; display: grid; align-content: start; }
.ann-preview-card { border: 1px solid var(--line); border-radius: 14px; padding: 18px 20px; background: var(--paper); box-shadow: 0 10px 30px rgba(15, 40, 80, .06); }
.ann-preview-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; color: var(--accent-ink); font-weight: 700; font-size: 13px; }
.ann-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.ann-arrow { margin-left: auto; }
.ann-preview :deep(.markdown) { font-size: 13px; line-height: 1.75; color: var(--ink-soft); }
.ann-preview :deep(.markdown p) { margin: 0 0 8px; }
.ann-preview :deep(.markdown img) { max-width: 100%; border-radius: 8px; }
.ann-ai-modal { width: min(560px, calc(100vw - 32px)); }
.ann-tip { padding: 10px 12px; border-radius: 8px; background: var(--accent-soft); color: var(--ink-soft); font-size: 12px; line-height: 1.7; }
@media (max-width: 800px) {
  .ann-bar, .ann-meta-grid { grid-template-columns: 1fr; display: grid; }
  .ann-workbench { grid-template-columns: 1fr; }
  .ann-pane + .ann-pane { border-left: 0; border-top: 1px solid var(--line); }
  .ann-textarea { min-height: 260px; }
}
</style>
