import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatApi } from '@/api/modules/chat'
import { notificationApi } from '@/api/modules/notification'
import { toNumber } from '@/utils/money'
import { useAuthStore } from './auth'

export const useBadgeStore = defineStore('badges', () => {
  const notifications = ref(0)
  const conversations = ref(0)

  async function refresh(): Promise<void> {
    const auth = useAuthStore()
    if (!auth.isLoggedIn) {
      notifications.value = 0
      conversations.value = 0
      return
    }
    const [notificationResult, conversationResult] = await Promise.allSettled([
      notificationApi.unreadCount(),
      chatApi.unreadCount(),
    ])
    notifications.value =
      notificationResult.status === 'fulfilled' ? toNumber(notificationResult.value.unreadCount) : 0
    conversations.value =
      conversationResult.status === 'fulfilled' ? toNumber(conversationResult.value.unreadCount) : 0
  }

  function reset(): void {
    notifications.value = 0
    conversations.value = 0
  }

  return { notifications, conversations, refresh, reset }
})
