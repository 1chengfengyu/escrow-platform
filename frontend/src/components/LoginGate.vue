<script setup lang="ts">
import { useRoute } from 'vue-router'
import EmptyState from './EmptyState.vue'

withDefaults(
  defineProps<{
    title?: string
    desc?: string
  }>(),
  {
    title: '登录后可见',
    desc: '任务广场、任务详情与沟通都需要登录。登录后即可查看完整信息。',
  },
)

const route = useRoute()
</script>

<template>
  <EmptyState :title="title" :desc="desc">
    <template #actions>
      <RouterLink class="btn btn--ink btn--sm" :to="{ path: '/login', query: { redirect: route.fullPath } }">
        去登录
      </RouterLink>
      <RouterLink class="btn btn--line btn--sm" to="/register">免费注册</RouterLink>
    </template>
  </EmptyState>
</template>
