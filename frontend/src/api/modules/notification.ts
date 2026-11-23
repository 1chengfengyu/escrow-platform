import { api } from '../http'
import type { NotificationView, PageView, UnreadCountView } from '../types'

export const notificationApi = {
  list(readFlag: number | undefined, page = 1, size = 20) {
    return api<PageView<NotificationView>>({
      url: '/api/notifications',
      method: 'get',
      params: { readFlag, page, size },
    })
  },
  markRead(id: string) {
    return api<void>({ url: `/api/notifications/${id}/read`, method: 'post' })
  },
  markAllRead() {
    return api<{ updated: number }>({ url: '/api/notifications/read-all', method: 'post' })
  },
  unreadCount() {
    return api<UnreadCountView>({ url: '/api/notifications/unread-count', method: 'get' })
  },
}
