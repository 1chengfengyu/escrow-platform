<script setup lang="ts">
import { computed } from 'vue'
import { formatMoney } from '@/utils/money'

const props = defineProps<{
  fen: string | number | null | undefined
  symbol?: boolean
  mutedZero?: boolean
}>()

const text = computed(() => formatMoney(props.fen, props.symbol !== false))
const isZero = computed(() => Number(props.fen ?? 0) === 0)
</script>

<template>
  <span class="money" :class="{ muted: mutedZero && isZero }">{{ text }}</span>
</template>
