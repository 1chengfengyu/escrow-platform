-- V3-c3：履约记录（FR-SIGN.START.02；SRS 4.2.2「履约记录」/ A.3.1）
-- 只追加：开始 / 交付 / 取消 / 超时取消四类事件，用于 FR-SIGN.START.02 计入劳动者履约记录
-- 同一签约同一事件类型至多一条：UNIQUE(contract_id, event_type)
-- 无物理外键、无 AUTO_INCREMENT、无逻辑删除、无金额列

CREATE TABLE engagement_performance_record (
    id          BIGINT      NOT NULL COMMENT '雪花ID',
    worker_id   BIGINT      NOT NULL COMMENT '劳动者用户ID（逻辑外键 platform_user.id）',
    contract_id BIGINT      NOT NULL COMMENT '签约ID（逻辑外键 engagement_contract.id）',
    event_type  VARCHAR(32) NOT NULL COMMENT '履约事件：PERFORMANCE_START/PERFORMANCE_DELIVER/PERFORMANCE_CANCEL/PERFORMANCE_TIMEOUT_CANCEL',
    occurred_at DATETIME(3) NOT NULL COMMENT '事件发生时间（业务时间，由应用层给值）',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_engagement_performance_record_event (contract_id, event_type),
    KEY idx_engagement_performance_record_worker_time (worker_id, occurred_at),
    CONSTRAINT ck_engagement_performance_record_event CHECK (event_type IN ('PERFORMANCE_START','PERFORMANCE_DELIVER','PERFORMANCE_CANCEL','PERFORMANCE_TIMEOUT_CANCEL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='履约记录（只追加；一场签约的同类事件至多一条）';
