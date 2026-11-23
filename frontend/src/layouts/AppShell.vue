<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useBadgeStore } from '@/stores/badges'
import { statusLabel } from '@/utils/status'
import AiQaDrawer from '@/components/AiQaDrawer.vue'
import type { ShellMenu } from './menu'

const props = defineProps<{ menus: ShellMenu[]; label: string; home: string; showMessages?: boolean }>()

const auth = useAuthStore()
const badges = useBadgeStore()
const route = useRoute()
const router = useRouter()

const notificationsPath = computed(() => (route.meta.section === 'employer' ? '/employer/notifications' : '/worker/notifications'))
const conversationsPath = computed(() => (route.meta.section === 'employer' ? '/employer/conversations' : '/worker/conversations'))

let timer: number | undefined

onMounted(() => {
  void badges.refresh()
  timer = window.setInterval(() => void badges.refresh(), 30_000)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

watch(
  () => route.fullPath,
  () => void badges.refresh(),
)

function logout(): void {
  auth.logout()
  badges.reset()
  void router.push('/')
}

async function onSwitchIdentity(event: Event): Promise<void> {
  const select = event.target as HTMLSelectElement
  const next = select.value
  if (!next || next === auth.identity) return
  try {
    await auth.switchIdentity(next)
    await router.push(auth.homePath(next))
  } catch {
    select.value = auth.identity
  }
}

function badgeValue(key: 'notifications' | 'conversations'): number {
  return key === 'notifications' ? badges.notifications : badges.conversations
}

function isMenuActive(to: string): boolean {
  if (to === '/worker' || to === '/employer' || to === '/admin') return route.path === to
  return route.path === to || route.path.startsWith(`${to}/`)
}
</script>

<template>
  <div class="shell">
    <header class="shell__head">
      <div class="shell__bar">
        <RouterLink class="brand" to="/">有活儿</RouterLink>
        <span class="shell__label">{{ props.label }}</span>
        <div class="shell__tools">
          <RouterLink v-if="props.showMessages !== false" class="tool" :to="conversationsPath">
            沟通
            <span v-if="badges.conversations > 0" class="count">{{ badges.conversations > 99 ? '99+' : badges.conversations }}</span>
          </RouterLink>
          <RouterLink v-if="props.showMessages !== false" class="tool" :to="notificationsPath">
            通知
            <span v-if="badges.notifications > 0" class="count">{{ badges.notifications > 99 ? '99+' : badges.notifications }}</span>
          </RouterLink>
          <label class="idswitch">
            <span class="idswitch__text">身份</span>
            <select :value="auth.identity" aria-label="切换身份" @change="onSwitchIdentity">
              <option v-for="item in auth.identities" :key="item" :value="item">
                {{ statusLabel('identity', item) }}
              </option>
            </select>
          </label>
          <RouterLink class="tool" :to="auth.homePath()">工作台</RouterLink>
          <button class="linkbtn" type="button" @click="logout">退出</button>
        </div>
      </div>
    </header>

    <div class="shell__body">
      <aside class="shell__side">
        <p class="shell__side-title">导航</p>
        <nav class="menu">
          <RouterLink
            v-for="item in props.menus"
            :key="item.to"
            :to="item.to"
            class="menu__item"
            :class="{ 'is-active': isMenuActive(item.to) }"
          >
            <span>{{ item.label }}</span>
            <span v-if="item.badge && badgeValue(item.badge) > 0" class="count">
              {{ badgeValue(item.badge) > 99 ? '99+' : badgeValue(item.badge) }}
            </span>
          </RouterLink>
        </nav>
        <div class="shell__side-foot">
          <p class="mono">{{ auth.nickname || '—' }}</p>
          <p class="muted">编号 {{ auth.userId || '—' }}</p>
        </div>
      </aside>
      <main class="shell__main">
        <RouterView />
      </main>
    </div>

    <AiQaDrawer />
  </div>
</template>
