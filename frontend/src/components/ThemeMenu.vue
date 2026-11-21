<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { applyTheme, state } from '../state'

const open = ref(false)
const root = ref(null)
const current = computed(() => state.themePreference || 'system')
const options = [
  { value: 'light', label: '浅色', desc: '始终使用亮色界面' },
  { value: 'dark', label: '深色', desc: '始终使用暗色界面' },
  { value: 'system', label: '系统', desc: '跟随操作系统外观' }
]

function choose(value) {
  applyTheme(value)
  open.value = false
}

function onDocumentClick(event) {
  if (root.value && !root.value.contains(event.target)) open.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="root" class="theme-menu-wrap">
    <button
      class="icon-btn theme-menu-trigger"
      type="button"
      :title="`主题：${current === 'light' ? '浅色' : current === 'dark' ? '深色' : '系统'}`"
      :aria-label="`切换主题，当前${current === 'light' ? '浅色' : current === 'dark' ? '深色' : '系统'}`"
      :aria-expanded="open"
      @click.stop="open = !open"
    >
      <svg v-if="state.theme === 'dark'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" />
      </svg>
      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
        <path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z" />
      </svg>
    </button>
    <div v-if="open" class="theme-menu-pop" @click.stop>
      <div class="theme-menu-head">外观</div>
      <button
        v-for="option in options"
        :key="option.value"
        type="button"
        class="theme-menu-option"
        :class="{ on: current === option.value }"
        @click="choose(option.value)"
      >
        <span class="theme-menu-copy">
          <b>{{ option.label }}</b>
          <small>{{ option.desc }}</small>
        </span>
        <svg v-if="current === option.value" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="m5 12 4 4 10-9" />
        </svg>
      </button>
    </div>
  </div>
</template>
