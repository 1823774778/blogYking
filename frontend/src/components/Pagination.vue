<script setup>
import { computed } from 'vue'

const props = defineProps({
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 10 }
})

const emit = defineEmits(['change'])

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))

const pages = computed(() => {
  const last = totalPages.value
  const current = props.page
  let start = Math.max(1, current - 2)
  let end = Math.min(last, start + 4)
  if (end - start < 4) start = Math.max(1, end - 4)
  const list = []
  for (let i = start; i <= end; i += 1) list.push(i)
  return list
})

function go(p) {
  if (p < 1 || p > totalPages.value || p === props.page) return
  emit('change', p)
}
</script>

<template>
  <div v-if="totalPages > 1" class="pager">
    <button type="button" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
    <button
      v-for="p in pages"
      :key="p"
      type="button"
      :class="{ on: p === page }"
      @click="go(p)"
    >
      {{ p }}
    </button>
    <button type="button" :disabled="page >= totalPages" @click="go(page + 1)">下一页</button>
  </div>
</template>
