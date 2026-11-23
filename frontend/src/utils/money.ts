const digits = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function toNumber(value: string | number | null | undefined): number {
  if (value === null || value === undefined || value === '') return 0
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

/**
 * 分 → 元文本，如 128460000 → "¥1,284,600.00"。
 */
export function formatMoney(fen: string | number | null | undefined, withSymbol = true): string {
  const amount = toNumber(fen) / 100
  const sign = amount < 0 ? '-' : ''
  const body = digits.format(Math.abs(amount))
  return `${sign}${withSymbol ? '¥' : ''}${body}`
}

/**
 * 元输入（字符串或数字）→ 分；非法返回 null。
 */
export function yuanToFen(input: string | number | null | undefined): number | null {
  if (input === null || input === undefined || input === '') return null
  const value = typeof input === 'number' ? input : Number(String(input).replace(/[,，\s¥]/g, ''))
  if (!Number.isFinite(value) || value < 0) return null
  return Math.round(value * 100)
}

/** 万分比 → 百分比文本，如 500 → "5.00%" */
export function formatRateBps(bps: string | number | null | undefined): string {
  return `${(toNumber(bps) / 100).toFixed(2)}%`
}

/** 评分 ×100 → 星级文本，如 450 → "4.50" */
export function formatRating(x100: string | number | null | undefined): string {
  return (toNumber(x100) / 100).toFixed(2)
}

/** 万分比费率 → 百分比文本，如 300 → "3%" */
export function formatFeeRate(rate: string | number | null | undefined): string {
  const value = toNumber(rate) / 100
  return `${Number.isInteger(value) ? value : value.toFixed(2)}%`
}
