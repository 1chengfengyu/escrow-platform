import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiResponse } from './types'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 静默处理 401：用于登录、恢复会话、公开页探测 */
    silentAuth?: boolean
  }
}

export class ApiError extends Error {
  /** 后端错误码（SRS 4.2 字符串）；本地校验错误用 -1 */
  readonly code: string | number
  readonly traceId: string
  readonly status: number

  constructor(code: string | number, message: string, traceId = '', status = 0) {
    super(message || '请求失败')
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
    this.status = status
  }

  get isUnauthorized(): boolean {
    return this.status === 401
  }

  get isForbidden(): boolean {
    return this.status === 403
  }

  get isNotFound(): boolean {
    return this.status === 404
  }
}

export function toApiError(raw: unknown): ApiError {
  if (raw instanceof ApiError) return raw
  if (raw instanceof Error) return new ApiError(-1, raw.message)
  return new ApiError(-1, '未知错误')
}

let unauthorizedHandler: (() => void) | null = null
let forbiddenHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

export function setForbiddenHandler(handler: () => void): void {
  forbiddenHandler = handler
}

const client = axios.create({
  baseURL: '',
  withCredentials: true,
  timeout: 20000,
  headers: { Accept: 'application/json' },
})

function httpMessage(status: number): string {
  if (status === 401) return '登录状态已失效，请重新登录'
  if (status === 403) return '当前身份无权访问该资源'
  if (status === 404) return '请求的资源不存在'
  if (status >= 500) return '服务暂时不可用，请稍后重试'
  return `请求失败（HTTP ${status}）`
}

/**
 * 统一请求：解包 ApiResponse，把业务码与 HTTP 错误统一成 ApiError。
 */
export async function api<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await client.request<ApiResponse<T>>(config)
    const body = response.data
    if (!body || typeof body.code !== 'string') {
      throw new ApiError(-1, '响应格式异常', '', response.status)
    }
    if (body.code !== 'SUCCESS') {
      throw new ApiError(body.code, body.message, body.traceId, response.status)
    }
    return body.data
  } catch (raw) {
    if (raw instanceof ApiError) throw raw
    const error = raw as {
      response?: { status?: number; data?: Partial<ApiResponse<unknown>> }
      message?: string
    }
    const status = error.response?.status ?? 0
    const body = error.response?.data
    const apiError = new ApiError(
      typeof body?.code === 'string' ? body.code : status || -1,
      body?.message || (status ? httpMessage(status) : '无法连接服务，请确认后端已在 8080 启动'),
      body?.traceId || '',
      status,
    )
    if (!config.silentAuth) {
      if (status === 401) unauthorizedHandler?.()
      else if (status === 403) forbiddenHandler?.()
    }
    throw apiError
  }
}

/** 下载二进制（审计导出），直接返回 Blob 与文件名。 */
export async function download(path: string, params?: object): Promise<Blob> {
  const response = await client.get<Blob>(path, { params, responseType: 'blob' })
  return response.data
}
