# 架构设计

## 1. 系统边界

系统是**模块化单体**（modular monolith），一个 Spring Boot 进程承载七个业务域，前端是一个独立的 Vue 3 单页应用。选择单体不是因为没有拆微服务的野心，而是因为：

- 资金链路要求强一致。发布、冻结、记账、生成名额必须在同一个本地事务里完成；拆成服务后要么引入分布式事务，要么接受最终一致，两者都会让「发布即托管」这个核心承诺变复杂。
- 当前规模不需要独立伸缩。域边界通过包结构与调用规则约束，将来按域拆服务时，一个域的 `api / application / domain / infrastructure` 可以整体搬走。
- 单体不等于一团。跨域只允许调用对方 `application` 层 Service，不允许直接依赖对方的 Mapper、实体或 SQL。

部署形态：

```
┌─────────────┐      HTTP      ┌──────────────────────────┐
│  Vue 3 SPA  │ ─────────────► │  Spring Boot 应用进程     │
└─────────────┘                │  identity / task / ...   │
                               └───────┬──────────┬───────┘
                                       │          │
                                 ┌─────▼───┐  ┌───▼─────┐
                                 │ MySQL 8 │  │ Redis 7 │
                                 └─────────┘  └─────────┘
                                       │
                              ┌────────┴────────┐
                              │ Kafka / MongoDB │  （可选 profile）
                              └─────────────────┘
```

## 2. 模块划分

| 域 | 职责 | 不负责 |
|---|---|---|
| `identity` | 注册、登录、会话、双身份、资质提交与审核 | 业务权限判定（由各域守卫执行） |
| `task` | 草稿、发布、名额、检索、详情、撤单、到期 | 冻结资金、记账（调 fund 域） |
| `engagement` | 报名、录用、合同、履约记录、交付物、验收、评价 | 结算金额计算（调 settlement 域） |
| `fund` | 账户、托管单、托管明细、分录、余额不变量 | 业务状态推进 |
| `settlement` | 结算、打款、打款尝试、人工复核、对账、差异处理 | 争议裁决（调 dispute 域） |
| `dispute` | 争议立案、证据、仲裁、执行退款或放款 | 直接改余额（调 fund 域） |
| `admin` | 治理看板、用户管理、资质审核、审计查询 | 业务规则实现（复用各域 Service） |
| `ai` | 查询理解、检索、重排、问答、画像、轨迹、成本 | 业务写操作（只读业务数据） |
| `common` | 统一响应、异常、安全、会话、审计、通知、聊天、缓存 | 业务语义 |

每个域内部四层：

```
api             Controller、请求/响应 record、参数校验
application     Service、事务边界、跨域编排
domain          实体、状态机、纯函数策略、领域异常
infrastructure  Mapper、外部适配器、缓存实现
```

规则：

1. `api` 不直接调 `infrastructure`。
2. `domain` 不依赖 Spring，状态机与策略是纯函数，可单测。
3. 跨域只调 `application` Service，事务边界在调用方。
4. 不允许跨域 join。需要别的域的数据时调 Service，不在 SQL 里拼表。

## 3. 核心数据模型

37 张表，按域分组：

| 域 | 表 |
|---|---|
| identity | `platform_user`、`platform_user_identity`、`platform_employer_qualification` |
| task | `task`、`task_slot` |
| engagement | `engagement_application`、`engagement_contract`、`engagement_performance_record`、`engagement_deliverable`、`engagement_review` |
| fund | `fund_account`、`fund_escrow_order`、`fund_escrow_detail`、`fund_journal_batch`、`fund_journal_entry` |
| settlement | `settlement`、`settlement_difference`、`payout_order`、`payout_verification`、`payout_manual_decision`、`recon_batch` |
| dispute | `dispute`、`dispute_evidence` |
| common | `notification`、`notification_dead_letter`、`audit_log`、`security_event`、`chat_conversation`、`chat_message`、`chat_read_cursor` |
| ai | `ai_trace`、`ai_worker_profile`、`ai_index_version`、`ai_index_stale_ref`、`ai_feedback`、`ai_cost_daily`、`ai_eval_result` |
| 调度 | `shedlock` |

约定：

- 主键雪花 ID，`BIGINT`；业务单据号 `VARCHAR(32)`，`_no` 结尾。
- 金额一律 `BIGINT` 存分，禁止浮点。
- 时间 `DATETIME(3)`，毫秒精度。
- 状态列 `VARCHAR(32)` 存大写枚举名。
- 不建物理外键；一致性由服务层与契约测试保证。
- 不启用逻辑删除；有账实体用状态表达生命周期。
- 任务状态与剩余名额不落库，实时派生。

## 4. 资金链路

### 4.1 发布即托管

```
开启事务
  ├─ 校验雇主身份、资质开关、余额
  ├─ 抢占任务发布锁（防重复提交）
  ├─ 生成 N 条 task_slot（待占用）
  ├─ 创建 fund_escrow_order + N 条 fund_escrow_detail
  ├─ 写分录：可用 → 冻结
  ├─ 更新 fund_account 余额
  └─ 提交
```

全部在一个本地事务内。任一步失败，名额、托管单、分录、余额一起回滚。

### 4.2 结算

```
验收通过
  ├─ 计算服务费：floor(payable * fee_rate / 10000)
  ├─ 写 settlement（数据库 CHECK 校验服务费）
  ├─ 写分录：冻结 → 待结算
  └─ 生成 payout_order
```

服务费只在下单时快照一次。后续费率调整不回溯已发布任务。

### 4.3 打款

打款是**不确定操作**：通道可能超时，但钱可能已经出去了。因此：

- `payout_order` 记录业务意图，`payout_verification` 记录每次查询通道的结果。
- 超时进入「执行中」，由定时任务查询通道状态；不允许直接重试。
- 明确失败才允许重试，重试次数与间隔受限。
- 状态仍不确定时转人工复核，写 `payout_manual_decision`，保留操作人与依据。

### 4.4 对账

- 日终生成 `recon_batch`，拉取通道流水与本地分录比对。
- 差异写 `settlement_difference`，分为「本地有通道无」「通道有本地无」「金额不一致」。
- 差异必须由人处理：补单、冲正或标记忽略，每种处理都留审计。
- 原始报文可归档到 MongoDB；默认 log 模式不依赖 Mongo。

### 4.5 记账不变量

- 同一批次借贷必平。
- 分录只追加，不更新、不删除。
- 账户余额 = 分录聚合结果；`FundLedgerGuard` 在每次记账后校验「可用 + 冻结 = 总额」。
- 业务单据号全局唯一，重复请求命中幂等键返回原结果。

## 5. 状态机

| 状态机 | 状态 |
|---|---|
| 名额 | 待占用 → 已占用 → 履约中 → 已交付 → 待结算 → 已完成 / 已退款 / 已释放 |
| 报名 | 待处理 → 已录用 / 已拒绝 / 已撤回 |
| 合同 | 待签署 → 生效 → 履约中 → 已完成 / 已终止 |
| 托管单 | 待托管 → 已托管 → 结算中 → 已结算 / 已退款 |
| 打款单 | 待执行 → 执行中 → 成功 / 失败 / 待人工 |
| 争议 | 待受理 → 审理中 → 已裁决 → 已执行 |
| 会话 | 打开 → 关闭 / 归档 |

状态机只回答「现在是什么事实」，**不作为业务守卫**。守卫直接判定名额集合、时间窗、资金状态与身份，避免状态字段被写错后绕过规则。

## 6. 权限与安全

- 认证：Spring Security + Redis Session，24 小时过期；登录失败计数与锁定。
- 授权：一个账号可同时是雇主与劳动者，业务授权以 `platform_user_identity` 为准；`platform_user.role` 只决定默认视图。
- 管理端分级：客服、财务、仲裁、超级管理员；敏感操作要求二次确认并写审计。
- 限流：登录、注册、AI 查询按 Redis 计数器限流，超限返回 429 + `Retry-After`。
- 越权：资源归属在 Service 层校验；草稿对非本人返回 404，不暴露存在性。
- 输出：统一异常处理不返回堆栈与 SQL；日志脱敏手机号、邮箱、证件号。
- 审计：管理端写操作记 `audit_log`；安全事件记 `security_event`。

## 7. 一致性与并发

| 场景 | 手段 |
|---|---|
| 名额超卖 | 条件更新 `UPDATE task_slot SET status=... WHERE id=? AND status=?`，影响行数为 0 即失败 |
| 重复录用 | 唯一索引 + 条件更新，冲突返回具体业务码 |
| 重复发布 | 发布锁 + 幂等键 |
| 余额并发 | 条件更新 `UPDATE ... SET balance = balance ± ? WHERE ... AND balance >= ?`，影响行数为 0 即失败 |
| 消息重复 | 发送失败降级直写收件箱 + `uk_notification_event` 唯一键兜底 |
| 缓存一致性 | 事务提交后驱逐，Cache-Aside，空值标记单独处理 |

## 8. 缓存

任务详情是读多写少的典型热点，采用两级缓存：

| 层 | 键 | TTL | 作用 |
|---|---|---|---|
| Caffeine | `taskId` | 60s + 抖动 | 单实例热点，挡 Redis 网络往返 |
| Redis | `task:detail:{taskId}` | 30min + 抖动 | 跨实例共享 |

缓存的是与观察者无关的 `TaskDetailSnapshot`，读出后再按当事人身份投影。这样不会出现「雇主看到劳动者的名额明细」这类越权。

写路径在事务提交后驱逐两层；缓存异常降级回源，不让详情接口 5xx。

## 9. 可观测性

- 每个请求生成或透传 `X-Trace-Id`，写入 MDC 与响应体 `traceId`。
- 结构化日志包含 `traceId`、用户 ID、路径、耗时、结果码。
- Actuator 只暴露 `health` 与 `info`；`health` 含 liveness/readiness。
- AI 调用单独记 `ai_trace`：阶段、耗时、token、是否回退、是否命中缓存；日成本原子累加到 `ai_cost_daily`。
- 关键业务指标可从表直接聚合：托管余额、待结算额、打款成功率、对账差异数、争议超时数。

## 10. 演进策略

版本按「先闭环、再加固、再扩展」推进：

- V0–V2：基础设施、账号、任务、托管、报名、合同。先把「发布—报名—签约」跑通。
- V3–V5：履约、验收、结算、打款、通知。补齐资金闭环。
- V6–V7：对账、争议、审计、限流、治理看板。补齐运营与风控。
- V8–V9：Kafka 异步、死信重放、性能与容量。
- V10：AI 检索与问答，独立评测基线。
- V11–V12：聊天、前端控制台、端到端验收。

每一版都有明确的验收项与已知代价，见 [version-history.md](version-history.md) 与 [decisions.md](decisions.md)。