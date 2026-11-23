<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    desc?: string
  }>(),
  { desc: '' },
)
</script>

<template>
  <div class="state">
    <p class="state__title">{{ title }}</p>
    <p v-if="desc" class="state__desc">{{ desc }}</p>
    <div v-if="$slots.actions" class="cluster" style="margin-top: 8px">
      <slot name="actions" />
    </div>
  </div>
</template>
