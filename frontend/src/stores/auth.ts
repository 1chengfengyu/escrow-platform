import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { identityApi } from '@/api/modules/identity'
import type { IdentityMeResponse, LoginResponse } from '@/api/types'

const LOGOUT_FLAG = 'escrow.logged-out'

export const useAuthStore = defineStore('auth', () => {
  const me = ref<IdentityMeResponse | null>(null)
  const loaded = ref(false)
  const restoring = ref(false)
  let pending: Promise<void> | null = null

  const isLoggedIn = computed(() => me.value !== null)
  const userId = computed(() => me.value?.userId ?? '')
  const nickname = computed(() => me.value?.nickname ?? '')
  const identity = computed(() => me.value?.currentIdentity ?? '')
  const identities = computed(() => me.value?.identities ?? [])
  const isAdmin = computed(() => identity.value === 'ADMIN')
  const isEmployer = computed(() => identity.value === 'EMPLOYER')
  const isWorker = computed(() => identity.value === 'WORKER')

  async function restore(force = false): Promise<void> {
    if (loaded.value && !force) return
    if (pending) return pending
    if (!force && sessionStorage.getItem(LOGOUT_FLAG) === '1') {
      loaded.value = true
      return
    }
    restoring.value = true
    pending = (async () => {
      try {
        me.value = await identityApi.me()
      } catch {
        me.value = null
      } finally {
        loaded.value = true
        restoring.value = false
        pending = null
      }
    })()
    return pending
  }

  async function login(account: string, password: string): Promise<LoginResponse> {
    const result = await identityApi.login({ account, password })
    sessionStorage.removeItem(LOGOUT_FLAG)
    me.value = {
      userId: result.userId,
      nickname: result.nickname,
      defaultRole: result.defaultRole,
      currentIdentity: result.currentIdentity,
      identities: result.identities,
    }
    loaded.value = true
    return result
  }

  async function switchIdentity(identityType: string): Promise<string> {
    const result = await identityApi.switchIdentity(identityType)
    if (me.value) me.value = { ...me.value, currentIdentity: result.currentIdentity }
    return result.currentIdentity
  }

  function logout(): void {
    me.value = null
    loaded.value = true
    sessionStorage.setItem(LOGOUT_FLAG, '1')
  }

  function homePath(identityType: string = identity.value): string {
    if (identityType === 'EMPLOYER') return '/employer'
    if (identityType === 'ADMIN') return '/admin'
    if (identityType === 'WORKER') return '/worker'
    return '/'
  }

  return {
    me,
    loaded,
    restoring,
    isLoggedIn,
    userId,
    nickname,
    identity,
    identities,
    isAdmin,
    isEmployer,
    isWorker,
    restore,
    login,
    switchIdentity,
    logout,
    homePath,
  }
})
