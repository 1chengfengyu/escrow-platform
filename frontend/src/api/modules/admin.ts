import { api, download } from '../http'
import type {
  AdminDashboardView,
  AdminUserView,
  AuditLogView,
  PageView,
  QualificationListItem,
  QualificationReviewRequest,
} from '../types'

export interface AuditQuery {
  bizType?: string
  bizNo?: string
  operatorId?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export const adminApi = {
  dashboard() {
    return api<AdminDashboardView>({ url: '/api/admin/dashboard', method: 'get' })
  },
  users(keyword: string | undefined, status: string | undefined, page = 1, size = 20) {
    return api<PageView<AdminUserView>>({
      url: '/api/admin/users',
      method: 'get',
      params: { keyword, status, page, size },
    })
  },
  disableUser(userId: string, reason?: string) {
    return api<AdminUserView>({
      url: `/api/admin/users/${userId}/disable`,
      method: 'post',
      data: { reason },
    })
  },
  enableUser(userId: string, reason?: string) {
    return api<AdminUserView>({
      url: `/api/admin/users/${userId}/enable`,
      method: 'post',
      data: { reason },
    })
  },
  qualifications(status?: string) {
    return api<QualificationListItem[]>({
      url: '/api/admin/qualifications',
      method: 'get',
      params: { status },
    })
  },
  reviewQualification(userId: string, body: QualificationReviewRequest) {
    return api<void>({
      url: `/api/admin/qualifications/${userId}/review`,
      method: 'post',
      data: body,
    })
  },
  audit(query: AuditQuery) {
    return api<PageView<AuditLogView>>({ url: '/api/admin/audit', method: 'get', params: query })
  },
  exportAudit(query: AuditQuery) {
    return download('/api/admin/audit/export', query)
  },
}
