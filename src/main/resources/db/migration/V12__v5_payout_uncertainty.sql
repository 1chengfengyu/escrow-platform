-- V5：不确定性处理的两张流水表。
--
-- 为什么不改 payout_order：
--   1. payout_order 一行是一次尝试，查证可能发生多次，塞进同一行会丢失历史；
--   2. 人工处置是「谁在什么时候按什么凭据把单子从待人工推到终态」，必须可追溯；
--   3. 只加表不动既有列，V4 数据可原样升级（project_rules §10「能回滚」）。
--
-- 两张表都只追加，不设 UPDATE 语义；处置结果在 payout_manual_decision 里表达。

CREATE TABLE payout_verification (
    id            BIGINT      NOT NULL COMMENT '雪花ID',
    payout_id     BIGINT      NOT NULL COMMENT '打款单ID，逻辑外键 payout_order.id',
    request_no    VARCHAR(32) NOT NULL COMMENT '被查证的打款请求号',
    result        VARCHAR(32) NOT NULL COMMENT '查证结论：SUCCESS / FAIL / NOT_FOUND / AMOUNT_MISMATCH / ERROR',
    actual_amount BIGINT      NULL     COMMENT '通道侧实际金额（分），查不到为空',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '查证时间',
    PRIMARY KEY (id),
    KEY idx_payout_verification_payout (payout_id, created_at),
    KEY idx_payout_verification_request (request_no),
    CONSTRAINT ck_payout_verification_result CHECK (result IN (
        'SUCCESS','FAIL','NOT_FOUND','AMOUNT_MISMATCH','ERROR'
    )),
    CONSTRAINT ck_payout_verification_amount CHECK (actual_amount IS NULL OR actual_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打款查证流水';

CREATE TABLE payout_manual_decision (
    id            BIGINT      NOT NULL COMMENT '雪花ID',
    payout_id     BIGINT      NOT NULL COMMENT '打款单ID，逻辑外键 payout_order.id',
    decision      VARCHAR(32) NOT NULL COMMENT '处置：CONFIRM_ACTUAL / ZERO_PAYMENT / TO_DIFFERENCE',
    actual_amount BIGINT      NULL     COMMENT '确认的实际金额（分）；ZERO_PAYMENT 为 0，TO_DIFFERENCE 为空',
    operator_id   BIGINT      NOT NULL COMMENT '处置人账号ID，逻辑外键 platform_user.id',
    remark        VARCHAR(500) NULL    COMMENT '处置说明 / 零扣款凭据编号',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '处置时间',
    PRIMARY KEY (id),
    KEY idx_payout_manual_decision_payout (payout_id, created_at),
    CONSTRAINT ck_payout_manual_decision_type CHECK (decision IN (
        'CONFIRM_ACTUAL','ZERO_PAYMENT','TO_DIFFERENCE'
    )),
    CONSTRAINT ck_payout_manual_decision_amount CHECK (actual_amount IS NULL OR actual_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打款人工处置记录';
