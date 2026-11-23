import { api } from '../http'
import type {
  AiProfile,
  AiProfileCommand,
  AiQa,
  AiSearch,
  AiTaskSearch,
  AiTrace,
  AiUnderstand,
} from '../types'

/**
 * AI 能力入口（V10 / V12.1）。
 *
 * 角色门禁在后端：`search` 只给雇主，`searchTasks` 与 `profile` 只给服务者。
 * 前端不自行判权隐藏入口，遇到 403 由统一拦截器处理。
 */
export const aiApi = {
  parse(query: string) {
    return api<AiUnderstand>({ url: '/api/ai/query/parse', method: 'post', data: { query } })
  },
  search(draftId: string, removedKeys: string[]) {
    return api<AiSearch>({ url: '/api/ai/search', method: 'post', data: { draftId, removedKeys } })
  },
  searchTasks(draftId: string, removedKeys: string[]) {
    return api<AiTaskSearch>({ url: '/api/ai/search/tasks', method: 'post', data: { draftId, removedKeys } })
  },
  ask(question: string) {
    return api<AiQa>({ url: '/api/ai/qa', method: 'post', data: { question } })
  },
  profile() {
    return api<AiProfile>({ url: '/api/ai/profile/me', method: 'get' })
  },
  saveProfile(body: AiProfileCommand) {
    return api<AiProfile>({ url: '/api/ai/profile', method: 'put', data: body })
  },
  feedback(traceNo: string, clicked: boolean, secondSearch = false) {
    return api<void>({ url: '/api/ai/feedback', method: 'post', data: { traceNo, clicked, secondSearch } })
  },
  /** 按链路号回放阶段明细；后端只允许看自己的链路。 */
  trace(traceNo: string) {
    return api<AiTrace>({ url: `/api/ai/trace/${traceNo}`, method: 'get' })
  },
}
