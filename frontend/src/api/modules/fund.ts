import { api } from '../http'
import type { FundBalanceView } from '../types'

export const fundApi = {
  balance() {
    return api<FundBalanceView>({ url: '/api/fund/accounts/me', method: 'get' })
  },
  recharge(amount: number, requestId: string) {
    return api<FundBalanceView>({
      url: '/api/fund/recharge',
      method: 'post',
      data: { amount, requestId },
    })
  },
}
