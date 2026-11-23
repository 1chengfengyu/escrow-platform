<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    desc?: string
    info?: boolean
  }>(),
  { title: '本期未开放', desc: '', info: false },
)
</script>

<template>
  <div class="notice" :class="{ 'notice--info': info }">
    <p class="notice__eyebrow">能力缺口</p>
    <p class="notice__title">{{ title }}</p>
    <p v-if="desc" class="notice__desc">{{ desc }}</p>
    <slot />
  </div>
</template>
