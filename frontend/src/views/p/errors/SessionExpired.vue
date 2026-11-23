<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const redirect = computed(() => (typeof route.query.redirect === 'string' ? route.query.redirect : ''))
</script>

<template>
  <div class="wrap">
    <div class="error-page">
      <p class="error-page__code">401</p>
      <h1 class="error-page__title">登录已失效</h1>
      <p class="state__desc">会话已过期。重新登录后会回到刚才的页面。</p>
      <div class="cluster" style="justify-content: center; margin-top: 8px">
        <RouterLink class="btn btn--ink" :to="{ path: '/login', query: redirect ? { redirect } : {} }">重新登录</RouterLink>
        <RouterLink class="btn btn--line" to="/">返回首页</RouterLink>
      </div>
    </div>
  </div>
</template>
