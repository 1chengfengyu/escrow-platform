/**
 * 后端 DTO 的前端映射。
 *
 * Jackson 全局把 Long/long 序列化成字符串（common/config/JacksonConfig），
 * 因此所有 ID 与金额/计数（long）在 JSON 里都是 string；int 才是 number。
 */

export type Long = string
export type Money = string

export interface ApiResponse<T> {
  /** 后端以字符串返回：成功固定 "SUCCESS"，失败为 SRS 4.2 的错误码 */
  code: string
  message: string
  data: T
  traceId: string
}

export interface PageView<T> {
  items: T[]
  page: number
  size: number
  total: string
  totalPages: number
}

/* ---------------- 任务 ---------------- */

export type TaskDisplayStatus =
  | 'TASK_DRAFT'
  | 'TASK_RECRUITING'
  | 'TASK_TAKEN'
  | 'TASK_IN_PROGRESS'
  | 'TASK_PENDING_ACCEPTANCE'
  | 'TASK_SETTLING'
  | 'TASK_IN_DISPUTE'
  | 'TASK_SETTLEMENT_ABNORMAL'
  | 'TASK_COMPLETED'
  | 'TASK_CANCELLED'
  | 'TASK_CLOSED'

export type SlotStatus =
  | 'SLOT_AVAILABLE'
  | 'SLOT_OCCUPIED'
  | 'SLOT_IN_PROGRESS'
  | 'SLOT_DELIVERED'
  | 'SLOT_PENDING_ARBITRATION'
  | 'SLOT_PENDING_SETTLEMENT'
  | 'SLOT_COMPLETED'
  | 'SLOT_REFUNDED'
  | 'SLOT_RELEASED'

export interface SlotItem {
  seqNo: number
  status: SlotStatus
}

export interface TaskListItemView {
  id: Long
  title: string
  category: string
  unitPrice: Money
  workLocation: string
  startTime: string
  endTime: string
  publishedAt: string
  remainingSlots: number
  hasOpenDispute: boolean
  hasSettlementAbnormal: boolean
}

export interface TaskDetailView {
  id: Long
  title: string
  description: string
  category: string
  unitPrice: Money
  slotCount: number
  startTime: string
  endTime: string
  workLocation: string
  settlementNote: string
  publishedAt: string | null
  cancelledAt: string | null
  remainingSlots: number
  slotOccupancy: SlotItem[]
  escrowAmount: Money | null
  partyView: boolean
  hasOpenDispute: boolean
  hasSettlementAbnormal: boolean
  status: TaskDisplayStatus
  recruitingClosed: boolean
}

export interface TaskCreatedView {
  id: Long
}

export interface TaskDraftRequest {
  title: string
  description: string
  category: string
  unitPrice: number
  slotCount: number
  startTime: string
  endTime: string
  workLocation: string
  settlementNote: string
}

export interface TaskSearchQuery {
  keyword?: string
  category?: string
  minPrice?: number
  maxPrice?: number
  from?: string
  to?: string
  location?: string
  page?: number
  size?: number
  sort?: 'published_at' | 'unit_price'
  order?: 'asc' | 'desc'
}

/* ---------------- 报名 / 签约 / 评价 ---------------- */

export type ApplicationStatus = 'APPLY_PENDING' | 'APPLY_HIRED' | 'APPLY_REJECTED' | 'APPLY_WITHDRAWN'

export interface ApplicationCreatedView {
  id: Long
}

export interface ApplicationListItemView {
  id: Long
  workerId: Long
  workerNickname: string
  status: ApplicationStatus
  appliedAt: string
}

export interface HireResultView {
  contractId: Long
}

export type ContractStatus =
  | 'CONTRACT_ACTIVE'
  | 'CONTRACT_STARTED'
  | 'CONTRACT_DELIVERED'
  | 'CONTRACT_COMPLETED'
  | 'CONTRACT_CANCELLED'

export interface ContractListItemView {
  contractId: Long
  taskId: Long
  slotId: Long
  status: ContractStatus
  effectiveAt: string
  startedAt: string | null
  deliveredAt: string | null
}

export interface ContractActionView {
  contractId: Long
  status: ContractStatus
}

export interface AcceptanceView {
  contractId: Long
  contractStatus: ContractStatus
  slotStatus: SlotStatus
  disputeId: Long | null
  settlementId: Long | null
  payoutId: Long | null
}

export interface DeliverRequest {
  images: string[]
  description: string
}

export interface ReviewView {
  reviewerId: Long
  reviewerNickname: string
  rating: number
  comment: string
  createdAt: string
}

export interface ReputationView {
  userId: Long
  completedContracts: string
  timeoutCancels: string
  timeoutRateBps: number
  averageRatingX100: number
  reviewCount: string
  reviews: PageView<ReviewView>
}

/* ---------------- 资金 ---------------- */

export interface FundBalanceView {
  available: Money
  frozen: Money
}

/* ---------------- 身份与资质 ---------------- */

export type IdentityType = 'EMPLOYER' | 'WORKER' | 'ADMIN'
export type UserStatus = 'ACTIVE' | 'DISABLED' | 'CLOSED'
export type SubjectType = 'INDIVIDUAL' | 'SOLE_PROPRIETOR'
export type ReviewStatus = 'UNSUBMITTED' | 'PENDING' | 'APPROVED' | 'REJECTED'

export interface LoginResponse {
  userId: Long
  nickname: string
  defaultRole: string
  currentIdentity: string
  identities: string[]
}

export interface IdentityMeResponse {
  userId: Long
  nickname: string
  defaultRole: string
  currentIdentity: string
  identities: string[]
}

export interface IdentitySwitchResponse {
  currentIdentity: string
}

export interface RegisterRequest {
  phone: string
  email: string
  password: string
  nickname: string
  role: string
}

export interface RegisterResponse {
  userId: Long
}

export interface UserCardResponse {
  userId: Long
  nickname: string
  phone: string
  email: string
  role: string
}

export interface QualificationView {
  status: ReviewStatus
  subjectType: SubjectType | null
  subjectName: string | null
  idLast4: string | null
  reviewReason: string | null
  reviewedAt: string | null
  publishEligible: boolean
}

export interface QualificationSubmitRequest {
  subjectType: string
  subjectName: string
  idLast4: string
}

export interface QualificationReviewRequest {
  decision: string
  reason?: string
}

export interface ReviewCreateRequest {
  rating: number
  comment: string
}

export interface AdminUserStatusRequest {
  reason?: string
}

export interface QualificationListItem {
  userId: Long
  nickname: string
  subjectType: SubjectType
  subjectName: string
  idLast4: string
  submittedAt: string
}

/* ---------------- 争议 ---------------- */

export type DisputeStatus = 'DISPUTE_PENDING' | 'DISPUTE_WITHDRAWN' | 'DISPUTE_RESOLVED'
export type DisputeResolution = 'RELEASE' | 'REFUND'
export type DisputeInitiatorType = 'EMPLOYER' | 'WORKER'

export interface DisputeEvidenceView {
  id: Long
  disputeId: Long
  submitterId: Long
  submitterType: string
  content: string
  imageUrls: string[]
  createdAt: string
}

export interface DisputeSummaryView {
  id: Long
  slotId: Long
  initiatorType: DisputeInitiatorType
  reason: string
  status: DisputeStatus
  withdrawCount: number
  createdAt: string
  pendingDays: string
}

export interface DisputeView {
  id: Long
  slotId: Long
  initiatorType: DisputeInitiatorType
  reason: string
  status: DisputeStatus
  resolution: DisputeResolution | null
  resolutionBasis: string | null
  resolverId: Long | null
  resolvedAt: string | null
  withdrawCount: number
  createdAt: string
  evidence: DisputeEvidenceView[]
}

export interface DisputeEvidenceRequest {
  content: string
  imageUrls: string[]
}

export interface DisputeResolveRequest {
  resolution: DisputeResolution
  basis: string
}

/* ---------------- 沟通 ---------------- */

export type ChatScope = 'INQUIRY' | 'APPLICATION' | 'CONTRACT'
export type ChatConversationStatus = 'OPEN' | 'ARCHIVED'
export type ChatMessageType = 'TEXT' | 'SYSTEM'

export interface ConversationListItemView {
  id: Long
  scope: ChatScope
  taskId: Long
  status: ChatConversationStatus
  lastMessageAt: string | null
  unreadCount: string
}

export interface ConversationView {
  id: Long
  scope: ChatScope
  taskId: Long
  taskTitle: string
  employerId: Long
  workerId: Long
  employerNickname: string
  workerNickname: string
  status: ChatConversationStatus
  lastMessageAt: string | null
  createdAt: string
}

export interface MessageView {
  id: Long
  conversationId: Long
  senderId: Long
  msgType: ChatMessageType
  content: string
  contactHint: boolean
  createdAt: string
}

export interface CreateConversationRequest {
  taskId: number
  scope?: ChatScope
  targetUserId?: number
}

export interface SendMessageRequest {
  content: string
  clientMessageId?: string
}

export interface UnreadCountView {
  unreadCount: string
}

/* ---------------- 站内信 ---------------- */

export interface NotificationView {
  id: Long
  bizType: string
  bizNo: string
  title: string
  content: string
  read: boolean
  createdAt: string
}

/* ---------------- 结算 / 打款 / 对账 ---------------- */

export type SettlementStatus =
  | 'SETTLEMENT_PENDING_GENERATE'
  | 'SETTLEMENT_CONFIRMED'
  | 'SETTLEMENT_PAYING'
  | 'SETTLEMENT_PENDING_RETRY'
  | 'SETTLEMENT_COMPLETED'
  | 'SETTLEMENT_VOIDED'

export type PayoutStatus =
  | 'PAYOUT_PENDING_EXECUTE'
  | 'PAYOUT_EXECUTING'
  | 'PAYOUT_SUCCESS'
  | 'PAYOUT_FAIL'
  | 'PAYOUT_PENDING_VERIFICATION'
  | 'PAYOUT_MANUAL_REVIEW'

export type PayoutManualDecisionType = 'CONFIRM_ACTUAL' | 'ZERO_PAYMENT' | 'TO_DIFFERENCE'

export type DifferenceStatus = 'SETTLEMENT_DIFF_PENDING' | 'SETTLEMENT_DIFF_HANDLED' | 'SETTLEMENT_DIFF_IGNORED'
export type DifferenceType = 'CHANNEL_MISSING' | 'LOST_RESPONSE' | 'AMOUNT_MISMATCH' | 'RESULT_UNDETERMINED'
export type DifferenceResolution = 'RECON_BOOK' | 'RECON_REVERSE' | 'RECON_ADJUST' | 'RECON_IGNORE'

export interface PayoutAdminView {
  id: Long
  settlementId: Long
  requestNo: string
  attemptSeq: number
  amount: Money
  actualAmount: Money | null
  status: PayoutStatus
  channelResult: string | null
  statusEnteredAt: string
  ageMinutes: string
}

export interface PayoutAgingAlert {
  payoutId: Long
  settlementId: Long
  requestNo: string
  status: PayoutStatus
  amount: Money
  statusEnteredAt: string
  ageMinutes: string
}

export interface PayoutDecisionRequest {
  decision: PayoutManualDecisionType
  actualAmount?: number
  remark?: string
}

export interface ReconDifferenceView {
  id: Long
  reconDate: string
  diffType: DifferenceType
  localRecordNo: string
  channelRecordNo: string
  localAmount: Money | null
  channelAmount: Money | null
  status: DifferenceStatus
  resolution: DifferenceResolution | null
  resolverId: Long | null
  resolvedAt: string | null
}

export interface ReconDecisionRequest {
  resolution: DifferenceResolution
  remark?: string
}

export interface ReconReport {
  reconDate: string
  batchId: Long | null
  status: string
  openingTotal: Money
  turnover: Money
  closingTotal: Money
  diffCount: number
  diffAmount: Money
  pendingCount: number
  identityOk: boolean
}

export interface ReconRun {
  batchId: Long
  reconDate: string
  localCount: number
  channelCount: number
  diffCount: number
  diffAmount: Money
  identityOk: boolean
}

/* ---------------- 管理端 ---------------- */

export interface AdminDashboardView {
  userCount: string
  taskCount: string
  pendingPayoutCount: string
  pendingDisputeCount: string
  pendingDifferenceCount: string
}

export interface AdminUserView {
  userId: Long
  nickname: string
  phone: string
  email: string
  role: string
  status: UserStatus
  createdAt: string
}

export interface AuditLogView {
  id: Long
  bizType: string
  bizNo: string
  action: string
  fromStatus: string | null
  toStatus: string | null
  operatorId: Long
  operatorRole: string
  detail: string
  createdAt: string
}

export interface DeadLetterView {
  id: Long
  topic: string
  payload: string
  errorMessage: string
  replayCount: number
  lastReplayedAt: string | null
  createdAt: string
}

/* ---------------- AI（V10 / V12.1） ---------------- */

export type AiSlotKey = 'skill' | 'priceMax' | 'location' | 'weekend' | 'headcount'

export interface AiSlotTag {
  key: AiSlotKey | string
  label: string
  value: string
}

export interface AiQuerySlots {
  skill: string | null
  /** Long：JSON 里是字符串 */
  priceMax: Money | null
  location: string | null
  weekend: boolean | null
  headcount: number | null
  keywords: string[]
  confidence: number
}

export interface AiUnderstand {
  draftId: string
  slots: AiQuerySlots
  tags: AiSlotTag[]
  degraded: boolean
  promptVersion: string
}

export interface AiSearchItem {
  userId: Money
  score: number
  skills: string[]
  expectedUnitPrice: Money
  location: string
  weekendAvailable: boolean
  ratingX100: number
  completedOrders: number
  reason: string
  evidenceFields: string[]
}

export interface AiSearch {
  items: AiSearchItem[]
  strategy: string
  bucket: number
  traceNo: string
  rerankApplied: boolean
  rerankRejected: boolean
  rerankNote: string | null
}

export interface AiTaskSearchItem {
  taskId: Money
  title: string
  category: string
  unitPrice: Money
  workLocation: string
  startTime: string
  endTime: string
  remainingSlots: number
  score: number
  reason: string
  evidenceFields: string[]
}

export interface AiTaskSearch {
  items: AiTaskSearchItem[]
  strategy: string
  bucket: number
  traceNo: string
  note: string | null
}

export interface AiQa {
  answer: string
  citations: string[]
  abstain: boolean
  degraded: boolean
  traceNo: string
}

export interface AiTraceCall {
  model: string
  tokens: number
  micros: number
  fallback: boolean
  cacheHit: boolean
}

export interface AiTraceStage {
  phase: string
  ms: number
  tokens: number
  degraded: boolean
  note: string
  calls: AiTraceCall[]
}

export interface AiTrace {
  traceNo: string
  userId: Money
  queryText: string
  strategy: string
  bucket: number
  resultCount: number
  totalTokens: number
  costMicros: number
  stages: AiTraceStage[]
  createdAt: string
}

export type AiProfileSource = 'USER_INPUT' | 'DERIVED' | 'MISSING'

export interface AiProfile {
  userId: Money
  skills: string[]
  experienceYears: number | null
  expectedUnitPrice: Money | null
  location: string | null
  weekendAvailable: boolean | null
  completedOrders: number | null
  ratingX100: number | null
  sources: Record<string, AiProfileSource>
  profileVersion: number
}

export interface AiProfileCommand {
  skills: string[]
  experienceYears: number | null
  expectedUnitPrice: number | null
  location: string | null
  weekendAvailable: boolean | null
}
