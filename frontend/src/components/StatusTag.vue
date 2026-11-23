<script setup lang="ts">
import { computed } from 'vue'
import { statusMeta, type StatusKind } from '@/utils/status'

const props = defineProps<{ kind: StatusKind; value: string | null | undefined }>()
const meta = computed(() => statusMeta(props.kind, props.value))
</script>

<template>
  <span class="stamp" :class="`stamp--${meta.tone}`">{{ meta.label }}</span>
</template>
