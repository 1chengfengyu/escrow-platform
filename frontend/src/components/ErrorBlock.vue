<script setup lang="ts">
import { computed } from 'vue'
import { toApiError, type ApiError } from '@/api/http'

const props = defineProps<{ error: unknown }>()
defineEmits<{ retry: [] }>()

const apiError = computed<ApiError>(() => toApiError(props.error))
const trace = computed(() => apiError.value.traceId)
</script>

<template>
  <div class="state state--error">
    <p class="state__title">{{ apiError.message }}</p>
    <p class="state__desc">
      错误码 <span class="mono">{{ apiError.code }}</span>
      <template v-if="apiError.status"> · HTTP {{ apiError.status }}</template>
    </p>
    <p v-if="trace" class="state__trace">追踪号 {{ trace }}</p>
    <div class="cluster" style="margin-top: 8px">
      <button class="btn btn--line btn--sm" type="button" @click="$emit('retry')">重试</button>
      <slot name="actions" />
    </div>
  </div>
</template>
