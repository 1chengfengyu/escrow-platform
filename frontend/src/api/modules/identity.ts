import { api } from '../http'
import type {
  IdentityMeResponse,
  IdentitySwitchResponse,
  LoginResponse,
  QualificationSubmitRequest,
  QualificationView,
  RegisterRequest,
  RegisterResponse,
  UserCardResponse,
} from '../types'

export const identityApi = {
  register(body: RegisterRequest) {
    return api<RegisterResponse>({ url: '/api/identity/register', method: 'post', data: body, silentAuth: true })
  },
  login(body: { account: string; password: string }) {
    return api<LoginResponse>({ url: '/api/identity/login', method: 'post', data: body, silentAuth: true })
  },
  me() {
    return api<IdentityMeResponse>({ url: '/api/identity/me', method: 'get', silentAuth: true })
  },
  switchIdentity(identityType: string) {
    return api<IdentitySwitchResponse>({
      url: '/api/identity/switch',
      method: 'post',
      data: { identityType },
    })
  },
  userCard(userId: string) {
    return api<UserCardResponse>({ url: `/api/identity/users/${userId}/card`, method: 'get', silentAuth: true })
  },
  qualification() {
    return api<QualificationView>({ url: '/api/identity/qualification', method: 'get' })
  },
  submitQualification(body: QualificationSubmitRequest) {
    return api<void>({ url: '/api/identity/qualification', method: 'post', data: body })
  },
}
