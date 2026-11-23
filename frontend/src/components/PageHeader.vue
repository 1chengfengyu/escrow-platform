<script setup lang="ts">
withDefaults(
  defineProps<{
    eyebrow?: string
    title: string
    desc?: string
  }>(),
  { eyebrow: '', desc: '' },
)
</script>

<template>
  <header class="page-head">
    <div class="page-head__main">
      <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
      <h1 class="page-title">{{ title }}</h1>
      <p v-if="desc" class="page-desc">{{ desc }}</p>
    </div>
    <div v-if="$slots.actions" class="page-actions">
      <slot name="actions" />
    </div>
  </header>
</template>
