<script setup lang="ts">
withDefaults(defineProps<{ rows?: number; label?: string }>(), { rows: 3, label: '正在加载' })
</script>

<template>
  <div class="loading" role="status" :aria-label="label">
    <span v-for="index in rows" :key="index" class="loading__bar" :style="{ width: `${100 - index * 8}%` }" />
  </div>
</template>
