<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import { statusLabel } from '@/utils/status'

const auth = useAuthStore()
</script>

<template>
  <div class="wrap">
    <div class="error-page">
      <p class="error-page__code">403</p>
      <h1 class="error-page__title">当前身份没有访问权限</h1>
      <p class="state__desc">
        这个页面属于另一个身份的工作台。当前身份是
        <span class="strong">{{ statusLabel('identity', auth.identity) }}</span>，可以在工作台右上角切换身份后再试。
      </p>
      <div class="cluster" style="justify-content: center; margin-top: 8px">
        <RouterLink class="btn btn--ink" :to="auth.homePath()">回到我的工作台</RouterLink>
        <RouterLink class="btn btn--line" to="/">返回首页</RouterLink>
      </div>
    </div>
  </div>
</template>
