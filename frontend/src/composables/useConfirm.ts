import { useDialog } from 'naive-ui'

export interface ConfirmOptions {
  title: string
  content: string
  positiveText?: string
  negativeText?: string
  danger?: boolean
}

/**
 * 返回 Promise<boolean> 的确认框，避免把确认逻辑散落到每个页面。
 */
export function useConfirm() {
  const dialog = useDialog()

  return function confirm(options: ConfirmOptions): Promise<boolean> {
    return new Promise((resolve) => {
      let settled = false
      const finish = (value: boolean): void => {
        if (settled) return
        settled = true
        resolve(value)
      }
      dialog[options.danger ? 'warning' : 'info']({
        title: options.title,
        content: options.content,
        positiveText: options.positiveText ?? '确定',
        negativeText: options.negativeText ?? '取消',
        onPositiveClick: () => finish(true),
        onNegativeClick: () => finish(false),
        onClose: () => finish(false),
        onMaskClick: () => finish(false),
      })
    })
  }
}
