<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  page: number
  totalPages: number
  total: string | number
}>()

const emit = defineEmits<{ change: [page: number] }>()

const totalText = computed(() => String(props.total ?? '0'))
const canPrev = computed(() => props.page > 1)
const canNext = computed(() => props.page < props.totalPages)

function go(page: number): void {
  if (page < 1 || page > props.totalPages || page === props.page) return
  emit('change', page)
}
</script>

<template>
  <div v-if="totalPages > 0" class="pager">
    <span class="pager__meta">第 {{ page }} / {{ totalPages }} 页 · 共 {{ totalText }} 条</span>
    <div class="cluster">
      <button class="btn btn--line btn--sm" type="button" :disabled="!canPrev" @click="go(page - 1)">上一页</button>
      <button class="btn btn--line btn--sm" type="button" :disabled="!canNext" @click="go(page + 1)">下一页</button>
    </div>
  </div>
</template>
