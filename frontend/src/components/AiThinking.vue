<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  title: { type: String, default: 'AI 正在思考' },
  steps: { type: Array, default: () => [] },
  note: { type: String, default: '正在等待模型返回，完成后会自动写入结果。' },
  compact: { type: Boolean, default: false },
  tone: { type: String, default: 'accent' }
})

const elapsed = ref(0)
let timer = null

const displaySteps = computed(() => props.steps.length
  ? props.steps
  : ['理解请求与上下文', '分析内容与约束', '整理结果与表达'])
const activeStep = computed(() => Math.min(displaySteps.value.length - 1, Math.floor(elapsed.value / 1.6)))
const elapsedText = computed(() => `已思考 ${elapsed.value}s`)

onMounted(() => {
  timer = window.setInterval(() => { elapsed.value += 1 }, 1000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="ai-thinking" :class="[`tone-${tone}`, { compact }]" role="status" aria-live="polite">
    <span class="ai-thinking-orb" aria-hidden="true">
      <i></i><i></i><i></i>
    </span>
    <div class="ai-thinking-main">
      <div class="ai-thinking-head">
        <strong>{{ title }}</strong>
        <span>{{ elapsedText }}</span>
      </div>
      <ul>
        <li
          v-for="(step, index) in displaySteps"
          :key="step"
          :class="{ done: index < activeStep, current: index === activeStep }"
        >
          <i aria-hidden="true"></i>
          <span>{{ step }}</span>
          <em v-if="index === activeStep">处理中</em>
        </li>
      </ul>
      <p v-if="note">{{ note }}</p>
    </div>
  </div>
</template>

<style scoped>
.ai-thinking {
  position: relative;
  display: flex;
  gap: 13px;
  padding: 15px 16px 14px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--ai-tone, var(--accent)) 24%, var(--line));
  border-radius: 12px;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--ai-tone, var(--accent)) 7%, var(--paper)), var(--paper) 62%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .45);
}

.ai-thinking.tone-green { --ai-tone: #2f8f5b; }
.ai-thinking.tone-amber { --ai-tone: #ad7a18; }
.ai-thinking.tone-rose { --ai-tone: #b85562; }

.ai-thinking::after {
  content: "";
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--ai-tone, var(--accent)), transparent);
  animation: ai-thinking-scan 2.2s linear infinite;
}

@keyframes ai-thinking-scan {
  from { transform: translateX(-55%); opacity: .25; }
  50% { opacity: .8; }
  to { transform: translateX(55%); opacity: .25; }
}

.ai-thinking-orb {
  position: relative;
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  overflow: hidden;
  border-radius: 13px;
  background: color-mix(in srgb, var(--ai-tone, var(--accent)) 15%, var(--paper));
  color: var(--ai-tone, var(--accent));
}

.ai-thinking-orb i {
  position: absolute;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ai-tone, var(--accent));
  opacity: .9;
  animation: ai-thinking-orbit 1.8s ease-in-out infinite;
}

.ai-thinking-orb i:nth-child(1) { transform: translate(-8px, 2px); animation-delay: 0s; }
.ai-thinking-orb i:nth-child(2) { transform: translate(7px, -6px); animation-delay: .35s; }
.ai-thinking-orb i:nth-child(3) { transform: translate(4px, 9px); animation-delay: .7s; }

@keyframes ai-thinking-orbit {
  0%, 100% { opacity: .35; scale: .8; }
  50% { opacity: 1; scale: 1.25; }
}

.ai-thinking-main {
  display: grid;
  gap: 7px;
  min-width: 0;
  flex: 1;
}

.ai-thinking-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ai-thinking-head strong {
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 650;
}

.ai-thinking-head span {
  color: var(--muted);
  font-size: 9.5px;
  font-variant-numeric: tabular-nums;
}

.ai-thinking ul {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ai-thinking li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 10.5px;
  line-height: 1.55;
  transition: color .2s ease;
}

.ai-thinking li > i {
  width: 5px;
  height: 5px;
  flex: 0 0 5px;
  border-radius: 50%;
  background: var(--line);
}

.ai-thinking li.done { color: var(--ink-soft); opacity: .58; }
.ai-thinking li.done > i { background: color-mix(in srgb, var(--ai-tone, var(--accent)) 60%, var(--line)); }

.ai-thinking li.current { color: var(--ink); opacity: 1; }
.ai-thinking li.current > i {
  background: var(--ai-tone, var(--accent));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ai-tone, var(--accent)) 14%, transparent);
  animation: ai-thinking-dot 1.1s ease-in-out infinite;
}

@keyframes ai-thinking-dot {
  0%, 100% { opacity: .55; }
  50% { opacity: 1; }
}

.ai-thinking li em {
  margin-left: auto;
  padding: 1px 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--ai-tone, var(--accent)) 11%, var(--paper));
  color: var(--ai-tone, var(--accent));
  font-size: 8.5px;
  font-style: normal;
  white-space: nowrap;
}

.ai-thinking p {
  margin: 0;
  color: var(--muted);
  font-size: 9.5px;
  line-height: 1.55;
}

.ai-thinking.compact { padding: 11px 12px; gap: 10px; }
.ai-thinking.compact .ai-thinking-orb { width: 32px; height: 32px; flex-basis: 32px; border-radius: 10px; }
.ai-thinking.compact .ai-thinking-orb i { width: 5px; height: 5px; }
.ai-thinking.compact .ai-thinking-head strong { font-size: 11px; }
.ai-thinking.compact ul { gap: 2px; }
.ai-thinking.compact li { font-size: 9.5px; }
.ai-thinking.compact p { display: none; }

[data-theme="dark"] .ai-thinking {
  background: linear-gradient(135deg, color-mix(in srgb, var(--ai-tone, var(--accent)) 9%, #18202f), #151c2b 62%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .04);
}

@media (prefers-reduced-motion: reduce) {
  .ai-thinking::after,
  .ai-thinking-orb i,
  .ai-thinking li.current > i {
    animation: none;
  }
}
</style>
