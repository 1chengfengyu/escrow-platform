import { api } from '../http'
import type {
  PageView,
  PayoutAdminView,
  PayoutAgingAlert,
  PayoutDecisionRequest,
  ReconDecisionRequest,
  ReconDifferenceView,
  ReconReport,
  ReconRun,
} from '../types'

export const settlementApi = {
  payouts(status: string | undefined, page = 1, size = 20) {
    return api<PageView<PayoutAdminView>>({
      url: '/api/admin/payouts',
      method: 'get',
      params: { status, page, size },
    })
  },
  aging() {
    return api<PayoutAgingAlert[]>({ url: '/api/admin/payouts/aging', method: 'get' })
  },
  decide(payoutId: string, body: PayoutDecisionRequest) {
    return api<PayoutAdminView>({
      url: `/api/admin/payouts/${payoutId}/decision`,
      method: 'post',
      data: body,
    })
  },
  differences(status: string | undefined, date: string | undefined, page = 1, size = 20) {
    return api<PageView<ReconDifferenceView>>({
      url: '/api/admin/recon/differences',
      method: 'get',
      params: { status, date, page, size },
    })
  },
  handleDifference(id: string, body: ReconDecisionRequest) {
    return api<ReconDifferenceView>({
      url: `/api/admin/recon/differences/${id}/handle`,
      method: 'post',
      data: body,
    })
  },
  reconReport(date: string) {
    return api<ReconReport>({ url: '/api/admin/recon/report', method: 'get', params: { date } })
  },
  runRecon(date: string) {
    return api<ReconRun>({ url: '/api/admin/recon/run', method: 'post', params: { date } })
  },
}
