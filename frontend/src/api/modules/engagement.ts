import { api } from '../http'
import type {
  AcceptanceView,
  ApplicationCreatedView,
  ApplicationListItemView,
  ContractActionView,
  ContractListItemView,
  DeliverRequest,
  HireResultView,
  PageView,
  ReputationView,
  ReviewCreateRequest,
  ReviewView,
} from '../types'

export const engagementApi = {
  apply(taskId: string) {
    return api<ApplicationCreatedView>({ url: `/api/tasks/${taskId}/applications`, method: 'post' })
  },
  applications(taskId: string, page = 1, size = 20) {
    return api<PageView<ApplicationListItemView>>({
      url: `/api/tasks/${taskId}/applications`,
      method: 'get',
      params: { page, size },
    })
  },
  hire(taskId: string, applicationId: string) {
    return api<HireResultView>({
      url: `/api/tasks/${taskId}/applications/${applicationId}/hire`,
      method: 'post',
    })
  },
  contracts(page = 1, size = 20) {
    return api<PageView<ContractListItemView>>({ url: '/api/contracts', method: 'get', params: { page, size } })
  },
  start(contractId: string) {
    return api<ContractActionView>({ url: `/api/contracts/${contractId}/start`, method: 'post' })
  },
  deliver(contractId: string, body: DeliverRequest) {
    return api<ContractActionView>({ url: `/api/contracts/${contractId}/deliver`, method: 'post', data: body })
  },
  cancel(contractId: string) {
    return api<ContractActionView>({ url: `/api/contracts/${contractId}/cancel`, method: 'post' })
  },
  acceptance(contractId: string, decision: 'PASS' | 'REJECT', reason?: string) {
    return api<AcceptanceView>({
      url: `/api/contracts/${contractId}/acceptance`,
      method: 'post',
      data: { decision, reason },
    })
  },
  review(contractId: string, body: ReviewCreateRequest) {
    return api<ReviewView>({ url: `/api/contracts/${contractId}/reviews`, method: 'post', data: body })
  },
  reputation(userId: string, page = 1, size = 10, silentAuth = false) {
    return api<ReputationView>({
      url: `/api/users/${userId}/reputation`,
      method: 'get',
      params: { page, size },
      silentAuth,
    })
  },
}
