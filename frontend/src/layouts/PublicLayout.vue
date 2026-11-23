<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useBadgeStore } from '@/stores/badges'

const auth = useAuthStore()
const badges = useBadgeStore()
const route = useRoute()
const router = useRouter()

const workspace = computed(() => auth.homePath())

function logout(): void {
  auth.logout()
  badges.reset()
  void router.push('/')
}
</script>

<template>
  <div class="site">
    <header class="site__head">
      <div class="site__bar">
        <RouterLink class="brand" to="/">有活儿</RouterLink>
        <nav class="site__nav">
          <RouterLink to="/tasks">任务广场</RouterLink>
          <RouterLink to="/how-it-works">如何运作</RouterLink>
          <RouterLink to="/help">帮助中心</RouterLink>
        </nav>
        <div class="site__account">
          <template v-if="auth.isLoggedIn">
            <RouterLink class="btn btn--ghost btn--sm" :to="workspace">进入工作台</RouterLink>
            <span class="site__user">{{ auth.nickname }}</span>
            <button class="linkbtn" type="button" @click="logout">退出</button>
          </template>
          <template v-else>
            <RouterLink class="linkbtn" :to="{ path: '/login', query: { redirect: route.fullPath } }">登录</RouterLink>
            <RouterLink class="btn btn--ink btn--sm" to="/register">免费注册</RouterLink>
          </template>
        </div>
      </div>
    </header>

    <main class="site__main">
      <RouterView />
    </main>

    <footer class="site__foot">
      <div class="site__foot-inner">
        <div>
          <p class="brand brand--sm">有活儿</p>
          <p class="muted">任务有人承接，报酬按约结算。</p>
        </div>
        <div class="site__foot-links">
          <RouterLink to="/how-it-works">如何运作</RouterLink>
          <RouterLink to="/help">帮助中心</RouterLink>
          <RouterLink to="/help/account-recovery">账号找回</RouterLink>
        </div>
        <p class="mono muted">演示环境 · 数据仅用于验收</p>
      </div>
    </footer>
  </div>
</template>
