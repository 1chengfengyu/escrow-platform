import { api } from '../http'
import type {
  ChatScope,
  ConversationListItemView,
  ConversationView,
  CreateConversationRequest,
  MessageView,
  PageView,
  SendMessageRequest,
  UnreadCountView,
} from '../types'

export const chatApi = {
  create(body: CreateConversationRequest) {
    return api<ConversationView>({ url: '/api/conversations', method: 'post', data: body })
  },
  list(scope: ChatScope | undefined, page = 1, size = 20) {
    return api<PageView<ConversationListItemView>>({
      url: '/api/conversations',
      method: 'get',
      params: { scope, page, size },
    })
  },
  detail(id: string) {
    return api<ConversationView>({ url: `/api/conversations/${id}`, method: 'get' })
  },
  messages(id: string, page = 1, size = 50, afterId?: string) {
    return api<PageView<MessageView>>({
      url: `/api/conversations/${id}/messages`,
      method: 'get',
      params: { page, size, afterId },
    })
  },
  send(id: string, body: SendMessageRequest) {
    return api<MessageView>({ url: `/api/conversations/${id}/messages`, method: 'post', data: body })
  },
  markRead(id: string) {
    return api<void>({ url: `/api/conversations/${id}/read`, method: 'post' })
  },
  unreadCount() {
    return api<UnreadCountView>({ url: '/api/conversations/unread-count', method: 'get' })
  },
  adminMessages(id: string, page = 1, size = 50, afterId?: string) {
    return api<PageView<MessageView>>({
      url: `/api/admin/conversations/${id}/messages`,
      method: 'get',
      params: { page, size, afterId },
    })
  },
}
