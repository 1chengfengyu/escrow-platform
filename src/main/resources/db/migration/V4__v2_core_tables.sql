-- V4：V2 九张核心表（FR-ACCT.xx 与 FR-FUND.xx，字段清单见 SRS 4.2.2「资金 / 结算 / 争议 / 差异」）。
-- 口径：docs/decisions.md #14【V2 补充】（_id / _no 分家、不建物理外键）、#20【V2 修订】（V2 新建表状态列 VARCHAR(32)）、#15（无 deleted）、#18（表名 / 索引命名）。
--
-- 三个刻意的选择：
--   1. fund_account 用生成列 owner_user_id / system_subject + 唯一索引，实现「USER 按 (user_id, subject) 唯一、SYSTEM 按 subject 全局唯一」。
--      普通复合唯一索引拦不住 NULL（多个 NULL 在唯一索引里互不冲突），USER 行 user_id 非空、SYSTEM 行 user_id 为空，必须把两种归属拆成各自的唯一列。
--   2. payout_order 用生成列 success_settlement_id + uk_payout_success，实现「同一结算单至多一条成功」的部分唯一。
--      MySQL 唯一索引允许多个 NULL，只有 PAYOUT_SUCCESS 行的 settlement_id 被抽出来，其余行生成列是 NULL 不触发约束。
--   3. fund_journal_batch 的幂等键是 uk_fund_journal_batch_biz (biz_type, biz_no)，不是 batch_no 生成规则。
--      插入路径必须是 INSERT → 撞唯一键 → 捕获后 SELECT 回查；禁止先 SELECT 再 INSERT（并发下会写出两个批次，借贷各自平、总额翻倍）。
--
-- 禁令：不建 platform_user；不动 V1__baseline.sql / V2__platform_user_identity.sql / V3__platform_employer_qualification.sql；
--       不建 task / task_slot / dispute_evidence / platform_worker_profile；不建物理外键；不加 deleted；不用 AUTO_INCREMENT。
--       fund_journal_batch.biz_type / event_type 刻意不设 CHECK：取值由 v2-c5 记账服务定稿，本卡只保证列宽 VARCHAR(32)。

CREATE TABLE fund_account (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    owner_type     VARCHAR(16)  NOT NULL COMMENT '归属类型：USER / SYSTEM',
    user_id        BIGINT       NULL     COMMENT 'USER 归属的用户ID，逻辑外键 platform_user.id；SYSTEM 为空',
    subject        VARCHAR(20)  NOT NULL COMMENT '科目：ACC_AVAIL / ACC_FROZEN / ACC_PLATFORM / ACC_TRANSIT / ACC_WITHDRAWN / ACC_ISSUED',
    balance        BIGINT       NOT NULL DEFAULT 0 COMMENT '余额（分），不得为负',
    version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，V2 只落列，拦截器在 v2-c5 接',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    owner_user_id  BIGINT GENERATED ALWAYS AS (CASE WHEN owner_type = 'USER' THEN user_id END) VIRTUAL COMMENT '生成列：仅 USER 行取 user_id，用于唯一索引',
    system_subject VARCHAR(20) GENERATED ALWAYS AS (CASE WHEN owner_type = 'SYSTEM' THEN subject END) VIRTUAL COMMENT '生成列：仅 SYSTEM 行取 subject，用于唯一索引',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_account_user (owner_user_id, subject),
    UNIQUE KEY uk_fund_account_system (system_subject),
    KEY idx_fund_account_user (user_id),
    CONSTRAINT ck_fund_account_owner CHECK (
        (owner_type = 'USER' AND user_id IS NOT NULL)
        OR (owner_type = 'SYSTEM' AND user_id IS NULL)
    ),
    CONSTRAINT ck_fund_account_subject CHECK (
        (owner_type = 'USER' AND subject IN ('ACC_AVAIL','ACC_FROZEN'))
        OR (owner_type = 'SYSTEM' AND subject IN ('ACC_PLATFORM','ACC_TRANSIT','ACC_WITHDRAWN','ACC_ISSUED'))
    ),
    CONSTRAINT ck_fund_account_balance CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资金账户';

CREATE TABLE fund_escrow_order (
    id           BIGINT      NOT NULL COMMENT '雪花ID',
    task_id      BIGINT      NOT NULL COMMENT '任务ID，逻辑外键 task.id；一任务一张托管单',
    total_amount BIGINT      NOT NULL COMMENT '托管总额（分）= 单价 × 名额数',
    fee_rate     INT         NOT NULL COMMENT '服务费率快照（万分数，0~10000）',
    status       VARCHAR(32) NOT NULL COMMENT '状态：ESCROW_ORDER_HELD / ESCROW_ORDER_CLOSED',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_escrow_order_task (task_id),
    CONSTRAINT ck_fund_escrow_order_amount CHECK (total_amount > 0),
    CONSTRAINT ck_fund_escrow_order_fee_rate CHECK (fee_rate BETWEEN 0 AND 10000),
    CONSTRAINT ck_fund_escrow_order_status CHECK (status IN ('ESCROW_ORDER_HELD','ESCROW_ORDER_CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='托管单';

CREATE TABLE fund_escrow_detail (
    id              BIGINT      NOT NULL COMMENT '雪花ID',
    escrow_order_id BIGINT      NOT NULL COMMENT '托管单ID，逻辑外键 fund_escrow_order.id',
    slot_id         BIGINT      NOT NULL COMMENT '名额ID，逻辑外键 task_slot.id；一名额一条明细',
    amount          BIGINT      NOT NULL COMMENT '该名额冻结金额（分）',
    status          VARCHAR(32) NOT NULL COMMENT '状态：ESCROW_DETAIL_PENDING_HOLD / ESCROW_DETAIL_HELD / ESCROW_DETAIL_RELEASING / ESCROW_DETAIL_RELEASED / ESCROW_DETAIL_REFUNDED / ESCROW_DETAIL_FROZEN',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_escrow_detail_slot (slot_id),
    KEY idx_fund_escrow_detail_order (escrow_order_id),
    CONSTRAINT ck_fund_escrow_detail_amount CHECK (amount > 0),
    CONSTRAINT ck_fund_escrow_detail_status CHECK (status IN (
        'ESCROW_DETAIL_PENDING_HOLD','ESCROW_DETAIL_HELD','ESCROW_DETAIL_RELEASING',
        'ESCROW_DETAIL_RELEASED','ESCROW_DETAIL_REFUNDED','ESCROW_DETAIL_FROZEN'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='托管明细（每名额一条）';

CREATE TABLE fund_journal_batch (
    id           BIGINT       NOT NULL COMMENT '雪花ID',
    batch_no     VARCHAR(32)  NOT NULL COMMENT '凭证批次号，全局唯一、生成后不变',
    biz_type     VARCHAR(32)  NOT NULL COMMENT '业务类型：记账入口的业务动作标识，与 biz_no 组成幂等键。V2 首批候选：ACCOUNT_INIT / RECHARGE / WITHDRAW / ESCROW_HOLD / ESCROW_RELEASE / SETTLE_ZERO_PAY / PAYOUT_SETTLE / PAYOUT_ROLLBACK / ESCROW_REFUND / RECON_BOOK / RECON_REVERSE / RECON_TRANSIT_BACK / RECON_ADJUST；由 v2-c5 定稿，本列不设 CHECK',
    biz_no       VARCHAR(32)  NOT NULL COMMENT '业务单据号，与 biz_type 组成幂等键；同一业务动作重放必须复用',
    event_type   VARCHAR(32)  NOT NULL COMMENT '事件类型：记账事件模板标识。V2 首批候选：ACCOUNT_ISSUE / ACCOUNT_WITHDRAW / ESCROW_HOLD / ESCROW_RELEASE / ESCROW_REFUND / PAYOUT_SETTLE / PAYOUT_REFUND / RECON_ADJUST；由 v2-c5 定稿，本列不设 CHECK',
    trigger_type VARCHAR(16)  NOT NULL COMMENT '触发方：USER / SYSTEM / OPERATOR',
    summary      VARCHAR(255) NOT NULL COMMENT '凭证摘要',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_journal_batch_no (batch_no),
    UNIQUE KEY uk_fund_journal_batch_biz (biz_type, biz_no),
    KEY idx_fund_journal_batch_created (created_at),
    CONSTRAINT ck_fund_journal_batch_trigger CHECK (trigger_type IN ('USER','SYSTEM','OPERATOR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='凭证批次';

CREATE TABLE fund_journal_entry (
    id               BIGINT      NOT NULL COMMENT '雪花ID',
    journal_batch_id BIGINT      NOT NULL COMMENT '凭证批次ID，逻辑外键 fund_journal_batch.id',
    account_id       BIGINT      NOT NULL COMMENT '账户ID，逻辑外键 fund_account.id；必填，系统科目指向系统账户',
    subject          VARCHAR(20) NOT NULL COMMENT '科目冗余：ACC_AVAIL / ACC_FROZEN / ACC_PLATFORM / ACC_TRANSIT / ACC_WITHDRAWN / ACC_ISSUED',
    direction        VARCHAR(8)  NOT NULL COMMENT '方向：DEBIT（借，流出）/ CREDIT（贷，流入）',
    amount           BIGINT      NOT NULL COMMENT '发生额（分），必须 > 0；0 元不生成分录',
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间',
    PRIMARY KEY (id),
    KEY idx_fund_journal_entry_batch (journal_batch_id),
    KEY idx_fund_journal_entry_account (account_id, created_at),
    CONSTRAINT ck_fund_journal_entry_subject CHECK (subject IN (
        'ACC_AVAIL','ACC_FROZEN','ACC_PLATFORM','ACC_TRANSIT','ACC_WITHDRAWN','ACC_ISSUED'
    )),
    CONSTRAINT ck_fund_journal_entry_direction CHECK (direction IN ('DEBIT','CREDIT')),
    CONSTRAINT ck_fund_journal_entry_amount CHECK (amount > 0 AND amount <= 999999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分录';

CREATE TABLE settlement (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    slot_id        BIGINT       NOT NULL COMMENT '名额ID，逻辑外键 task_slot.id；一名额一张结算单',
    settlement_no  VARCHAR(32)  NOT NULL COMMENT '结算单号，全局唯一；兼作 payment_intent_no，不另设列',
    payable_amount BIGINT       NOT NULL COMMENT '应结金额（分）',
    fee_amount     BIGINT       NOT NULL COMMENT '服务费（分）= floor(应结 × 费率 ÷ 10000)',
    net_amount     BIGINT       NOT NULL COMMENT '实付金额（应打款金额，分）；实际出款见 payout_order.actual_amount',
    fee_rate       INT          NOT NULL COMMENT '服务费率快照（万分数，0~10000）',
    status         VARCHAR(32)  NOT NULL COMMENT '状态：SETTLEMENT_PENDING_GENERATE / SETTLEMENT_CONFIRMED / SETTLEMENT_PAYING / SETTLEMENT_PENDING_RETRY / SETTLEMENT_COMPLETED / SETTLEMENT_VOIDED',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_slot (slot_id),
    UNIQUE KEY uk_settlement_no (settlement_no),
    KEY idx_settlement_status (status),
    CONSTRAINT ck_settlement_amount CHECK (payable_amount > 0 AND fee_amount >= 0 AND net_amount >= 0),
    CONSTRAINT ck_settlement_sum CHECK (fee_amount + net_amount = payable_amount),
    CONSTRAINT ck_settlement_fee CHECK (fee_amount = FLOOR(payable_amount * fee_rate / 10000)),
    CONSTRAINT ck_settlement_fee_rate CHECK (fee_rate BETWEEN 0 AND 10000),
    CONSTRAINT ck_settlement_status CHECK (status IN (
        'SETTLEMENT_PENDING_GENERATE','SETTLEMENT_CONFIRMED','SETTLEMENT_PAYING',
        'SETTLEMENT_PENDING_RETRY','SETTLEMENT_COMPLETED','SETTLEMENT_VOIDED'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='结算单';

CREATE TABLE payout_order (
    id                    BIGINT       NOT NULL COMMENT '雪花ID',
    settlement_id         BIGINT       NOT NULL COMMENT '结算单ID，逻辑外键 settlement.id；不对它整体加唯一约束',
    request_no            VARCHAR(32)  NOT NULL COMMENT 'payout_request_no，全局唯一，每次尝试新生成',
    attempt_seq           INT          NOT NULL COMMENT '尝试序号，从 1 开始，同一结算单内递增',
    amount                BIGINT       NOT NULL COMMENT '请求金额 X（分）',
    actual_amount         BIGINT       NULL     COMMENT '通道实际出款金额 Y（分），确认前为空；成功态必填',
    channel_result        VARCHAR(20)  NULL     COMMENT '通道结果：SUCCESS / FAIL / TIMEOUT / LOST_RESPONSE',
    status                VARCHAR(32)  NOT NULL COMMENT '状态：PAYOUT_PENDING_EXECUTE / PAYOUT_EXECUTING / PAYOUT_SUCCESS / PAYOUT_FAIL / PAYOUT_PENDING_VERIFICATION / PAYOUT_MANUAL_REVIEW',
    status_entered_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '进入当前状态的时间，账龄唯一口径',
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    success_settlement_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'PAYOUT_SUCCESS' THEN settlement_id END) VIRTUAL COMMENT '生成列：仅成功态取 settlement_id，用于部分唯一',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payout_order_request (request_no),
    UNIQUE KEY uk_payout_order_attempt (settlement_id, attempt_seq),
    UNIQUE KEY uk_payout_success (success_settlement_id),
    KEY idx_payout_order_status (status, status_entered_at),
    CONSTRAINT ck_payout_order_amount CHECK (amount > 0),
    CONSTRAINT ck_payout_order_actual CHECK (actual_amount IS NULL OR actual_amount >= 0),
    CONSTRAINT ck_payout_order_channel CHECK (
        channel_result IS NULL OR channel_result IN ('SUCCESS','FAIL','TIMEOUT','LOST_RESPONSE')
    ),
    CONSTRAINT ck_payout_order_status CHECK (status IN (
        'PAYOUT_PENDING_EXECUTE','PAYOUT_EXECUTING','PAYOUT_SUCCESS',
        'PAYOUT_FAIL','PAYOUT_PENDING_VERIFICATION','PAYOUT_MANUAL_REVIEW'
    )),
    CONSTRAINT ck_payout_order_attempt CHECK (attempt_seq >= 1),
    CONSTRAINT ck_payout_success_actual CHECK (status <> 'PAYOUT_SUCCESS' OR actual_amount IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打款单（每次尝试一条）';

CREATE TABLE dispute (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    slot_id        BIGINT       NOT NULL COMMENT '名额ID，逻辑外键 task_slot.id；不唯一，允许历史多张',
    initiator_type VARCHAR(16)  NOT NULL COMMENT '发起方：EMPLOYER / WORKER',
    reason         VARCHAR(500) NOT NULL COMMENT '争议理由，≤500 字',
    status         VARCHAR(32)  NOT NULL COMMENT '状态：DISPUTE_PENDING / DISPUTE_WITHDRAWN / DISPUTE_RESOLVED',
    resolution     VARCHAR(16)  NULL     COMMENT '处置结论：RELEASE（判定放款）/ REFUND（判定退款）；未裁决为空',
    resolver_id    BIGINT       NULL     COMMENT '裁决人账号ID，逻辑外键 platform_user.id',
    resolved_at    DATETIME(3)  NULL     COMMENT '裁决时间',
    withdraw_count INT          NOT NULL DEFAULT 0 COMMENT '同一名额下发起→撤回的累计轮次，0~2',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_dispute_slot (slot_id, status),
    CONSTRAINT ck_dispute_initiator CHECK (initiator_type IN ('EMPLOYER','WORKER')),
    CONSTRAINT ck_dispute_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 500),
    CONSTRAINT ck_dispute_status CHECK (status IN ('DISPUTE_PENDING','DISPUTE_WITHDRAWN','DISPUTE_RESOLVED')),
    CONSTRAINT ck_dispute_resolution CHECK (resolution IS NULL OR resolution IN ('RELEASE','REFUND')),
    CONSTRAINT ck_dispute_withdraw_count CHECK (withdraw_count BETWEEN 0 AND 2),
    CONSTRAINT ck_dispute_resolved CHECK (
        (status = 'DISPUTE_RESOLVED' AND resolution IS NOT NULL AND resolver_id IS NOT NULL AND resolved_at IS NOT NULL)
        OR (status <> 'DISPUTE_RESOLVED' AND resolution IS NULL AND resolver_id IS NULL AND resolved_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='争议单';

CREATE TABLE settlement_difference (
    id                BIGINT      NOT NULL COMMENT '雪花ID',
    recon_date        DATE        NOT NULL COMMENT '对账日',
    diff_type         VARCHAR(24) NOT NULL COMMENT '差异类型：CHANNEL_MISSING / LOST_RESPONSE / AMOUNT_MISMATCH / RESULT_UNDETERMINED',
    local_record_no   VARCHAR(32) NOT NULL COMMENT '本地记录号：打款单请求号 payout_request_no',
    channel_record_no VARCHAR(32) NULL     COMMENT '通道记录号：通道流水号，缺失为空',
    local_amount      BIGINT      NULL     COMMENT '本地金额（分）',
    channel_amount    BIGINT      NULL     COMMENT '通道金额（分）',
    status            VARCHAR(32) NOT NULL COMMENT '状态：SETTLEMENT_DIFF_PENDING / SETTLEMENT_DIFF_HANDLED / SETTLEMENT_DIFF_IGNORED',
    resolution        VARCHAR(24) NULL     COMMENT '处置结果：RECON_BOOK（补记）/ RECON_REVERSE（冲正）/ RECON_ADJUST（差额调整）/ RECON_IGNORE（忽略并说明）',
    resolver_id       BIGINT      NULL     COMMENT '处置人账号ID，逻辑外键 platform_user.id',
    resolved_at       DATETIME(3) NULL     COMMENT '处置时间',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_difference (recon_date, local_record_no, diff_type),
    KEY idx_settlement_difference_status (status, recon_date),
    CONSTRAINT ck_settlement_difference_type CHECK (diff_type IN (
        'CHANNEL_MISSING','LOST_RESPONSE','AMOUNT_MISMATCH','RESULT_UNDETERMINED'
    )),
    CONSTRAINT ck_settlement_difference_status CHECK (status IN (
        'SETTLEMENT_DIFF_PENDING','SETTLEMENT_DIFF_HANDLED','SETTLEMENT_DIFF_IGNORED'
    )),
    CONSTRAINT ck_settlement_difference_resolution CHECK (
        resolution IS NULL OR resolution IN ('RECON_BOOK','RECON_REVERSE','RECON_ADJUST','RECON_IGNORE')
    ),
    CONSTRAINT ck_settlement_difference_amount CHECK (
        (local_amount IS NULL OR local_amount >= 0)
        AND (channel_amount IS NULL OR channel_amount >= 0)
    ),
    CONSTRAINT ck_settlement_difference_amount_mismatch CHECK (
        diff_type <> 'AMOUNT_MISMATCH' OR (local_amount IS NOT NULL AND channel_amount IS NOT NULL)
    ),
    CONSTRAINT ck_settlement_difference_resolved CHECK (
        (status = 'SETTLEMENT_DIFF_PENDING' AND resolution IS NULL AND resolver_id IS NULL AND resolved_at IS NULL)
        OR (status = 'SETTLEMENT_DIFF_HANDLED' AND resolution IN ('RECON_BOOK','RECON_REVERSE','RECON_ADJUST') AND resolver_id IS NOT NULL AND resolved_at IS NOT NULL)
        OR (status = 'SETTLEMENT_DIFF_IGNORED' AND resolution = 'RECON_IGNORE' AND resolver_id IS NOT NULL AND resolved_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对账差异单';
