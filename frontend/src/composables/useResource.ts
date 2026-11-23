import { onMounted, ref, shallowRef, type Ref, type ShallowRef } from 'vue'
import { toApiError, type ApiError } from '../api/http'

/**
 * 统一处理「加载 / 空 / 错误」三态的资源读取。
 */
export function useResource<T>(fetcher: () => Promise<T>, options: { immediate?: boolean } = {}) {
  const data = shallowRef<T | null>(null) as ShallowRef<T | null>
  const loading = ref(false)
  const error = ref<ApiError | null>(null)
  let sequence = 0

  async function load(): Promise<void> {
    const current = ++sequence
    loading.value = true
    error.value = null
    try {
      const result = await fetcher()
      if (current === sequence) data.value = result
    } catch (raw) {
      if (current === sequence) error.value = toApiError(raw)
    } finally {
      if (current === sequence) loading.value = false
    }
  }

  if (options.immediate !== false) onMounted(load)

  return { data, loading, error, load }
}

export function useAsyncAction() {
  const running = ref(false)
  const error = ref<ApiError | null>(null)

  async function run<T>(task: () => Promise<T>): Promise<T | null> {
    if (running.value) return null
    running.value = true
    error.value = null
    try {
      return await task()
    } catch (raw) {
      error.value = toApiError(raw)
      return null
    } finally {
      running.value = false
    }
  }

  return { running, error, run }
}

export type { Ref }
