<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import http from '../../api'
import { toast } from '../../state'
import MarkdownBody from '../../components/MarkdownBody.vue'
import AiThinking from '../../components/AiThinking.vue'

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

function closeAnnAi() {
  if (aiLoading.value) return
  aiOpen.value = false
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
    <div v-if="loading" class="adm-loading">正在读取公告…</div>

    <section v-else class="ann-studio">
      <header class="ann-studio-head">
        <div class="ann-heading">
          <span class="adm-card-kicker">ANNOUNCEMENT</span>
          <div class="ann-title-line">
            <h1>公告管理</h1>
            <span class="ann-status-pill" :class="{ on: form.enabled }">{{ form.enabled ? '正在展示' : '未展示' }}</span>
          </div>
          <p>首页公告横幅的内容、跳转与 AI 起草。保存后立即同步到前台。</p>
        </div>
        <div class="ann-head-actions">
          <button class="btn btn-ghost btn-sm" type="button" :disabled="uploading" @click="pickImage">{{ uploading ? '上传中…' : '插入图片' }}</button>
          <button class="btn btn-ghost btn-sm ai-btn" type="button" @click="openAi">✦ AI 生成公告</button>
          <button class="btn btn-sm" type="button" :disabled="saving || loading" @click="save">{{ saving ? '保存中…' : '保存公告' }}</button>
        </div>
      </header>

      <div class="ann-status-grid">
        <label class="ann-toggle-card" :class="{ on: form.enabled }">
          <span class="ann-toggle-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M4 10v4l12 5V5L4 10Z" /><path d="M8 15v4h4v-3M18 8a4 4 0 0 1 0 8" />
            </svg>
          </span>
          <span class="ann-toggle-copy">
            <strong>在前台展示这条公告</strong>
            <small>关闭后内容仍会保留，首页不显示</small>
          </span>
          <input v-model="form.enabled" type="checkbox" />
          <i class="adm-switch" aria-hidden="true"></i>
        </label>

        <div class="ann-ai-card" :class="{ ok: aiAvailable }">
          <span class="ann-ai-mark" aria-hidden="true">✦</span>
          <div>
            <strong>{{ aiAvailable ? 'AI 公告已就绪' : 'AI 公告未开启' }}</strong>
            <small>{{ aiAvailable ? '写要点后可直接生成草稿' : '需要先在 AI 设置中启用能力' }}</small>
          </div>
          <router-link to="/admin/settings?tab=ai">{{ aiAvailable ? 'AI 设置' : '去开启' }}</router-link>
        </div>
      </div>

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
          <div class="ann-pane-head">
            <span><b>编辑</b><small>Markdown</small></span>
            <span class="ann-char-count">{{ chars }} / 2000</span>
          </div>
          <textarea v-model="form.content" class="ann-textarea" maxlength="2000" placeholder="写一条简短、清楚的公告……"></textarea>
          <div class="ann-editor-foot">
            <span>支持 Markdown、换行与图片</span>
            <button class="btn-text" type="button" @click="openAi">✦ 用 AI 起草</button>
          </div>
        </div>
        <div class="ann-pane ann-preview-pane">
          <div class="ann-pane-head"><span><b>预览</b><small>首页效果</small></span><span class="ann-live-dot">LIVE</span></div>
          <div class="ann-preview">
            <div v-if="form.content.trim()" class="ann-preview-card">
              <div class="ann-preview-head">
                <span class="ann-dot"></span>
                <span>{{ form.title || '站点公告' }}</span>
                <span v-if="form.link" class="ann-arrow">→</span>
              </div>
              <MarkdownBody :content="form.content" />
            </div>
            <div v-else class="ann-preview-empty">
              <span aria-hidden="true">✦</span>
              <p>左侧输入内容后，这里会显示首页公告效果</p>
            </div>
          </div>
        </div>
      </section>

      <footer class="ann-studio-foot">
        <span>公告建议控制在 80–200 字；AI 只负责起草，发布前请人工确认日期、活动与链接。</span>
        <span>{{ form.link ? '点击公告会跳转到设定链接' : '未设置跳转链接' }}</span>
      </footer>
    </section>

    <input ref="fileInput" type="file" accept="image/*" hidden @change="uploadImage" />

    <div v-if="aiOpen" class="adm-modal-wrap" @click.self="closeAnnAi">
      <div class="adm-modal ann-ai-modal">
        <div class="adm-modal-head">
          <div><span class="adm-card-kicker">AI ANNOUNCEMENT</span><h3>AI 生成公告</h3></div>
          <button class="adm-icon-btn" type="button" :disabled="aiLoading" @click="closeAnnAi">×</button>
        </div>
        <div class="adm-modal-body">
          <AiThinking
            v-if="aiLoading"
            title="AI 正在起草公告"
            :steps="['读取公告要点与语气', '判断需要保留的关键信息', '控制篇幅与表达方式', '生成公告正文']"
            note="生成完成后会写入左侧公告正文，弹窗不会提前关闭。"
          />
          <template v-else>
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
          </template>
        </div>
        <div class="adm-modal-foot">
          <button class="btn btn-ghost btn-sm" type="button" :disabled="aiLoading" @click="closeAnnAi">取消</button>
          <button class="btn btn-sm" type="button" :disabled="aiLoading" @click="generate">{{ aiLoading ? '思考中…' : '生成公告' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.announcement-page { max-width: 1180px; }

.ann-studio {
  border: 1px solid var(--line);
  border-radius: 16px;
  overflow: hidden;
  background: var(--paper);
  box-shadow: 0 18px 50px rgba(22, 40, 60, .07);
}

.ann-studio-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px 20px;
  border-bottom: 1px solid var(--line);
  background:
    radial-gradient(circle at 4% 0%, var(--accent-soft), transparent 38%),
    var(--paper);
}

.ann-heading { display: grid; gap: 7px; min-width: 0; }
.ann-title-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ann-title-line h1 { margin: 0; font-size: 22px; letter-spacing: -.02em; }
.ann-heading p { margin: 0; color: var(--muted); font-size: 11.5px; line-height: 1.6; }

.ann-status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface, var(--accent-soft));
  color: var(--muted);
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: .02em;
}
.ann-status-pill::before { content: ""; width: 5px; height: 5px; border-radius: 50%; background: var(--muted); }
.ann-status-pill.on { border-color: #b7e4cb; background: #effaf3; color: #067a4b; }
.ann-status-pill.on::before { background: #12a866; box-shadow: 0 0 0 3px rgba(18, 168, 102, .12); }

.ann-head-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; flex-wrap: wrap; justify-content: flex-end; }
.ai-btn { color: #1d4ed8; border-color: #bfdbfe; background: #eff6ff; }

.ann-status-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(240px, .8fr);
  gap: 12px;
  padding: 16px 24px 0;
}

.ann-toggle-card {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 58px;
  padding: 11px 14px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface, var(--accent-soft));
  cursor: pointer;
  transition: .18s ease;
}
.ann-toggle-card.on { border-color: #b7e4cb; background: #f6fdf9; }
.ann-toggle-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 9px;
  background: var(--paper);
  color: var(--accent-ink);
}
.ann-toggle-icon svg { width: 17px; height: 17px; }
.ann-toggle-copy { display: grid; gap: 3px; min-width: 0; flex: 1; }
.ann-toggle-copy strong { color: var(--ink); font-size: 12.5px; }
.ann-toggle-copy small { color: var(--muted); font-size: 10px; }
.ann-toggle-card input { position: absolute; opacity: 0; pointer-events: none; }
.ann-toggle-card .adm-switch { position: relative; flex: 0 0 38px; margin: 0; }
.ann-toggle-card input:checked + .adm-switch { background: #12a866; }
.ann-toggle-card input:checked + .adm-switch::after { transform: translateX(16px); }

.ann-ai-card {
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 58px;
  padding: 11px 14px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface, var(--accent-soft));
}
.ann-ai-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 9px;
  background: var(--paper);
  color: #b45309;
  font-size: 16px;
}
.ann-ai-card > div { display: grid; gap: 3px; min-width: 0; flex: 1; }
.ann-ai-card strong { font-size: 12.5px; color: var(--ink); }
.ann-ai-card small { color: var(--muted); font-size: 10px; }
.ann-ai-card a { color: var(--accent-ink); font-size: 10.5px; white-space: nowrap; text-decoration: none; }
.ann-ai-card.ok { border-color: #bfe3d2; background: #f5fcf8; }
.ann-ai-card.ok .ann-ai-mark { color: #047857; }

.ann-meta-grid .field label {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ann-meta-grid .field .meta {
  display: inline;
  font-size: 9.5px;
  letter-spacing: 0;
}
.ann-meta-grid {
  display: grid;
  grid-template-columns: 1.25fr 1fr;
  gap: 14px;
  padding: 16px 24px;
}

.ann-workbench {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  height: min(44vh, 440px);
  min-height: 360px;
  margin: 0 24px;
  border: 1px solid var(--line);
  border-radius: 12px;
  overflow: hidden;
  background: var(--paper);
}

.ann-pane { display: flex; flex-direction: column; min-width: 0; }
.ann-pane + .ann-pane { border-left: 1px solid var(--line); }
.ann-pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 44px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--surface, var(--accent-soft));
  color: var(--ink-soft);
}
.ann-pane-head > span:first-child { display: flex; align-items: baseline; gap: 7px; }
.ann-pane-head b { color: var(--ink); font-size: 12px; letter-spacing: .03em; }
.ann-pane-head small { color: var(--muted); font-size: 9.5px; font-weight: 400; }
.ann-char-count { color: var(--muted); font-size: 9.5px; font-variant-numeric: tabular-nums; }
.ann-live-dot { color: #067a4b; font-size: 9px; font-weight: 700; letter-spacing: .12em; }
.ann-live-dot::before { content: ""; display: inline-block; width: 5px; height: 5px; margin-right: 4px; border-radius: 50%; background: #12a866; vertical-align: 1px; }

.ann-textarea {
  flex: 1;
  min-height: 260px;
  padding: 18px;
  border: 0;
  outline: 0;
  resize: none;
  background: var(--paper);
  color: var(--ink);
  font: 13.5px/1.85 ui-monospace, SFMono-Regular, Menlo, monospace;
}
.ann-editor-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 14px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 9.5px;
}
.btn-text { padding: 0; border: 0; background: transparent; color: var(--accent-ink); font-size: 10.5px; cursor: pointer; }

.ann-preview-pane { background: var(--surface, transparent); }
.ann-preview { flex: 1; padding: 20px; overflow: auto; display: grid; align-content: start; }
.ann-preview-card {
  border: 1px solid var(--line);
  border-radius: 13px;
  padding: 17px 19px;
  background: var(--paper);
  box-shadow: 0 10px 30px rgba(15, 40, 80, .06);
}
.ann-preview-head { display: flex; align-items: center; gap: 8px; margin-bottom: 9px; color: var(--accent-ink); font-weight: 700; font-size: 12.5px; }
.ann-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.ann-arrow { margin-left: auto; }
.ann-preview :deep(.markdown) { font-size: 12.5px; line-height: 1.75; color: var(--ink-soft); }
.ann-preview :deep(.markdown p) { margin: 0 0 8px; }
.ann-preview :deep(.markdown img) { max-width: 100%; border-radius: 8px; }
.ann-preview-empty { display: grid; place-content: center; justify-items: center; gap: 9px; min-height: 220px; color: var(--muted); text-align: center; }
.ann-preview-empty > span { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 11px; background: var(--accent-soft); color: var(--accent-ink); font-size: 16px; }
.ann-preview-empty p { margin: 0; max-width: 220px; font-size: 11px; line-height: 1.7; }

.ann-studio-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 24px 15px;
  color: var(--muted);
  font-size: 9.5px;
  line-height: 1.6;
}

.ann-ai-modal { width: min(560px, calc(100vw - 32px)); }
.ann-tip { padding: 10px 12px; border-radius: 8px; background: var(--accent-soft); color: var(--ink-soft); font-size: 12px; line-height: 1.7; }

@media (max-width: 900px) {
  .ann-studio-head { flex-direction: column; }
  .ann-head-actions { width: 100%; justify-content: flex-start; }
  .ann-status-grid { grid-template-columns: 1fr; }
  .ann-meta-grid { grid-template-columns: 1fr; }
  .ann-workbench { grid-template-columns: 1fr; height: auto; }
  .ann-pane + .ann-pane { border-left: 0; border-top: 1px solid var(--line); }
  .ann-textarea { min-height: 240px; }
  .ann-preview { min-height: 260px; }
  .ann-studio-foot { flex-direction: column; align-items: flex-start; }
}

[data-theme="dark"] .ann-status-pill.on {
  border-color: rgba(18, 168, 102, .28);
  background: rgba(18, 168, 102, .09);
  color: #7ee2b3;
}

[data-theme="dark"] .ann-status-pill.on::before {
  background: #42d392;
  box-shadow: 0 0 0 3px rgba(66, 211, 146, .12);
}

[data-theme="dark"] .ai-btn {
  border-color: rgba(96, 165, 250, .26);
  background: rgba(37, 99, 235, .12);
  color: #93c5fd;
}

[data-theme="dark"] .ann-toggle-card.on {
  border-color: rgba(18, 168, 102, .3);
  background: rgba(18, 168, 102, .07);
}

[data-theme="dark"] .ann-ai-card.ok {
  border-color: rgba(18, 168, 102, .28);
  background: rgba(18, 168, 102, .07);
}

[data-theme="dark"] .ann-ai-card.ok .ann-ai-mark {
  color: #7ee2b3;
}

</style>
