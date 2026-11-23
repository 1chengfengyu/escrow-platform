import { api } from '../http'
import type {
  DisputeEvidenceRequest,
  DisputeEvidenceView,
  DisputeResolveRequest,
  DisputeSummaryView,
  DisputeView,
} from '../types'

export const disputeApi = {
  open(contractId: string, reason: string) {
    return api<string>({ url: `/api/contracts/${contractId}/disputes`, method: 'post', data: { reason } })
  },
  detail(disputeId: string) {
    return api<DisputeView>({ url: `/api/disputes/${disputeId}`, method: 'get' })
  },
  addEvidence(disputeId: string, body: DisputeEvidenceRequest) {
    return api<DisputeEvidenceView>({
      url: `/api/disputes/${disputeId}/evidence`,
      method: 'post',
      data: body,
    })
  },
  evidenceFromChat(disputeId: string, messageIds: string[]) {
    return api<DisputeEvidenceView[]>({
      url: `/api/disputes/${disputeId}/evidence/from-chat`,
      method: 'post',
      data: { messageIds },
    })
  },
  withdraw(disputeId: string) {
    return api<void>({ url: `/api/disputes/${disputeId}/withdraw`, method: 'post' })
  },
  adminList(status?: string) {
    return api<DisputeSummaryView[]>({ url: '/api/admin/disputes', method: 'get', params: { status } })
  },
  adminDetail(disputeId: string) {
    return api<DisputeView>({ url: `/api/admin/disputes/${disputeId}`, method: 'get' })
  },
  adminOverdue(days = 7) {
    return api<DisputeSummaryView[]>({ url: '/api/admin/disputes/overdue', method: 'get', params: { days } })
  },
  adminResolve(disputeId: string, body: DisputeResolveRequest) {
    return api<{ disputeId: string; payoutId: string | null }>({
      url: `/api/admin/disputes/${disputeId}/resolve`,
      method: 'post',
      data: body,
    })
  },
}
