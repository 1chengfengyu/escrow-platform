import { api } from '../http'
import type {
  PageView,
  TaskCreatedView,
  TaskDetailView,
  TaskDraftRequest,
  TaskListItemView,
  TaskSearchQuery,
} from '../types'

export const taskApi = {
  list(query: TaskSearchQuery, silentAuth = false) {
    return api<PageView<TaskListItemView>>({ url: '/api/tasks', method: 'get', params: query, silentAuth })
  },
  detail(id: string, silentAuth = false) {
    return api<TaskDetailView>({ url: `/api/tasks/${id}`, method: 'get', silentAuth })
  },
  create(body: TaskDraftRequest) {
    return api<TaskCreatedView>({ url: '/api/tasks', method: 'post', data: body })
  },
  update(id: string, body: TaskDraftRequest) {
    return api<void>({ url: `/api/tasks/${id}`, method: 'put', data: body })
  },
  publish(id: string) {
    return api<void>({ url: `/api/tasks/${id}/publish`, method: 'post' })
  },
  cancel(id: string) {
    return api<void>({ url: `/api/tasks/${id}/cancel`, method: 'post' })
  },
  expire(id: string) {
    return api<void>({ url: `/api/tasks/${id}/expire`, method: 'post' })
  },
}
