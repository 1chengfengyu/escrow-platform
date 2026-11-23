export type Tone = 'muted' | 'active' | 'warn' | 'danger' | 'done'

export interface StatusMeta {
  label: string
  tone: Tone
}

export type StatusKind =
  | 'task'
  | 'slot'
  | 'application'
  | 'contract'
  | 'dispute'
  | 'resolution'
  | 'initiator'
  | 'chatScope'
  | 'conversation'
  | 'message'
  | 'payout'
  | 'settlement'
  | 'difference'
  | 'differenceType'
  | 'differenceResolution'
  | 'payoutDecision'
  | 'identity'
  | 'user'
  | 'subject'
  | 'review'

type Dictionary = Record<string, StatusMeta>

export const STATUS: Record<StatusKind, Dictionary> = {
  task: {
    TASK_DRAFT: { label: '草稿', tone: 'muted' },
    TASK_RECRUITING: { label: '招募中', tone: 'active' },
    TASK_TAKEN: { label: '名额已满', tone: 'warn' },
    TASK_IN_PROGRESS: { label: '进行中', tone: 'active' },
    TASK_PENDING_ACCEPTANCE: { label: '待验收', tone: 'warn' },
    TASK_SETTLING: { label: '结算中', tone: 'active' },
    TASK_IN_DISPUTE: { label: '争议中', tone: 'danger' },
    TASK_SETTLEMENT_ABNORMAL: { label: '结算异常', tone: 'danger' },
    TASK_COMPLETED: { label: '已完成', tone: 'done' },
    TASK_CANCELLED: { label: '已取消', tone: 'muted' },
    TASK_CLOSED: { label: '已关闭', tone: 'muted' },
  },
  slot: {
    SLOT_AVAILABLE: { label: '可报名', tone: 'active' },
    SLOT_OCCUPIED: { label: '已录用', tone: 'warn' },
    SLOT_IN_PROGRESS: { label: '履约中', tone: 'active' },
    SLOT_DELIVERED: { label: '已交付', tone: 'warn' },
    SLOT_PENDING_ARBITRATION: { label: '待裁决', tone: 'danger' },
    SLOT_PENDING_SETTLEMENT: { label: '待结算', tone: 'warn' },
    SLOT_COMPLETED: { label: '已完成', tone: 'done' },
    SLOT_REFUNDED: { label: '已退款', tone: 'muted' },
    SLOT_RELEASED: { label: '已放款', tone: 'done' },
  },
  application: {
    APPLY_PENDING: { label: '待处理', tone: 'warn' },
    APPLY_HIRED: { label: '已录用', tone: 'done' },
    APPLY_REJECTED: { label: '已拒绝', tone: 'muted' },
    APPLY_WITHDRAWN: { label: '已撤回', tone: 'muted' },
  },
  contract: {
    CONTRACT_ACTIVE: { label: '待开始', tone: 'warn' },
    CONTRACT_STARTED: { label: '履约中', tone: 'active' },
    CONTRACT_DELIVERED: { label: '已交付', tone: 'warn' },
    CONTRACT_COMPLETED: { label: '已完成', tone: 'done' },
    CONTRACT_CANCELLED: { label: '已取消', tone: 'muted' },
  },
  dispute: {
    DISPUTE_PENDING: { label: '待裁决', tone: 'danger' },
    DISPUTE_WITHDRAWN: { label: '已撤回', tone: 'muted' },
    DISPUTE_RESOLVED: { label: '已裁决', tone: 'done' },
  },
  resolution: {
    RELEASE: { label: '判定放款', tone: 'done' },
    REFUND: { label: '判定退款', tone: 'warn' },
  },
  initiator: {
    EMPLOYER: { label: '雇主发起', tone: 'active' },
    WORKER: { label: '劳动者发起', tone: 'active' },
  },
  chatScope: {
    INQUIRY: { label: '咨询', tone: 'muted' },
    APPLICATION: { label: '报名沟通', tone: 'active' },
    CONTRACT: { label: '履约沟通', tone: 'active' },
  },
  conversation: {
    OPEN: { label: '进行中', tone: 'active' },
    ARCHIVED: { label: '已归档', tone: 'muted' },
  },
  message: {
    TEXT: { label: '消息', tone: 'muted' },
    SYSTEM: { label: '系统', tone: 'warn' },
  },
  payout: {
    PAYOUT_PENDING_EXECUTE: { label: '待执行', tone: 'warn' },
    PAYOUT_EXECUTING: { label: '执行中', tone: 'active' },
    PAYOUT_SUCCESS: { label: '已成功', tone: 'done' },
    PAYOUT_FAIL: { label: '失败', tone: 'danger' },
    PAYOUT_PENDING_VERIFICATION: { label: '待查证', tone: 'danger' },
    PAYOUT_MANUAL_REVIEW: { label: '待人工', tone: 'warn' },
  },
  settlement: {
    SETTLEMENT_PENDING_GENERATE: { label: '待生成', tone: 'warn' },
    SETTLEMENT_CONFIRMED: { label: '已确认', tone: 'active' },
    SETTLEMENT_PAYING: { label: '打款中', tone: 'active' },
    SETTLEMENT_PENDING_RETRY: { label: '待重试', tone: 'warn' },
    SETTLEMENT_COMPLETED: { label: '已完成', tone: 'done' },
    SETTLEMENT_VOIDED: { label: '已作废', tone: 'muted' },
  },
  difference: {
    SETTLEMENT_DIFF_PENDING: { label: '待处理', tone: 'danger' },
    SETTLEMENT_DIFF_HANDLED: { label: '已处理', tone: 'done' },
    SETTLEMENT_DIFF_IGNORED: { label: '已忽略', tone: 'muted' },
  },
  differenceType: {
    CHANNEL_MISSING: { label: '通道缺失', tone: 'danger' },
    LOST_RESPONSE: { label: '响应丢失', tone: 'warn' },
    AMOUNT_MISMATCH: { label: '金额不一致', tone: 'danger' },
    RESULT_UNDETERMINED: { label: '结果未知', tone: 'warn' },
  },
  differenceResolution: {
    RECON_BOOK: { label: '补记', tone: 'active' },
    RECON_REVERSE: { label: '冲正', tone: 'warn' },
    RECON_ADJUST: { label: '差额调整', tone: 'warn' },
    RECON_IGNORE: { label: '忽略', tone: 'muted' },
  },
  payoutDecision: {
    CONFIRM_ACTUAL: { label: '确认实际到账', tone: 'active' },
    ZERO_PAYMENT: { label: '确认零支付', tone: 'warn' },
    TO_DIFFERENCE: { label: '转差异单', tone: 'danger' },
  },
  identity: {
    EMPLOYER: { label: '雇主', tone: 'active' },
    WORKER: { label: '劳动者', tone: 'active' },
    ADMIN: { label: '管理员', tone: 'active' },
  },
  user: {
    ACTIVE: { label: '正常', tone: 'done' },
    DISABLED: { label: '已禁用', tone: 'danger' },
    CLOSED: { label: '已注销', tone: 'muted' },
  },
  subject: {
    INDIVIDUAL: { label: '个人', tone: 'muted' },
    SOLE_PROPRIETOR: { label: '个体工商户', tone: 'muted' },
  },
  review: {
    UNSUBMITTED: { label: '未提交', tone: 'muted' },
    PENDING: { label: '待审核', tone: 'warn' },
    APPROVED: { label: '已通过', tone: 'done' },
    REJECTED: { label: '已驳回', tone: 'danger' },
  },
}

export function statusMeta(kind: StatusKind, value: string | null | undefined): StatusMeta {
  if (!value) return { label: '—', tone: 'muted' }
  return STATUS[kind][value] ?? { label: value, tone: 'muted' }
}

export function statusLabel(kind: StatusKind, value: string | null | undefined): string {
  return statusMeta(kind, value).label
}

export function statusOptions(kind: StatusKind): Array<{ label: string; value: string }> {
  return Object.entries(STATUS[kind]).map(([value, meta]) => ({ value, label: meta.label }))
}

/** 任务可执行动作（由状态字典驱动，页面不写 if 链） */
export const TASK_ACTIONS: Record<string, Array<'publish' | 'cancel' | 'expire'>> = {
  TASK_DRAFT: ['publish', 'cancel'],
  TASK_RECRUITING: ['cancel', 'expire'],
}

/** 签约可执行动作 */
export const CONTRACT_ACTIONS: Record<string, Array<'start' | 'deliver' | 'cancel' | 'acceptance' | 'review'>> = {
  CONTRACT_ACTIVE: ['start', 'cancel'],
  CONTRACT_STARTED: ['deliver', 'cancel'],
  CONTRACT_DELIVERED: [],
  CONTRACT_COMPLETED: ['review'],
  CONTRACT_CANCELLED: [],
}

export function can(kind: 'task' | 'contract', status: string | null | undefined, action: string): boolean {
  if (!status) return false
  const map = kind === 'task' ? TASK_ACTIONS : CONTRACT_ACTIONS
  return (map[status] ?? []).includes(action as never)
}
