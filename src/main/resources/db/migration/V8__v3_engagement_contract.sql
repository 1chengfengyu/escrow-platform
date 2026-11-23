-- V3-c2：签约表（FR-SIGN.HIRE.01；SRS 4.2.2 / A.3.7）
-- 有效签约唯一：MySQL 无部分唯一索引，用生成列 active_slot_id 实现（取消后可重新录用）
-- 一份报名一生只可录用一次：UNIQUE(application_id)
-- 无物理外键、无 AUTO_INCREMENT、无逻辑删除；名额计数与任务状态均不落库

CREATE TABLE engagement_contract (
    id                 BIGINT      NOT NULL COMMENT '雪花ID',
    task_id            BIGINT      NOT NULL COMMENT '任务ID（逻辑外键 task.id；冗余以避免跨域 join）',
    slot_id            BIGINT      NOT NULL COMMENT '名额ID（逻辑外键 task_slot.id）',
    application_id     BIGINT      NOT NULL COMMENT '报名ID（逻辑外键 engagement_application.id）',
    worker_id          BIGINT      NOT NULL COMMENT '劳动者用户ID（逻辑外键 platform_user.id）',
    status             VARCHAR(32) NOT NULL COMMENT '签约状态：CONTRACT_ACTIVE/CONTRACT_STARTED/CONTRACT_DELIVERED/CONTRACT_COMPLETED/CONTRACT_CANCELLED',
    effective_at       DATETIME(3) NOT NULL COMMENT '生效时间（录用成功时刻）',
    started_at         DATETIME(3) NULL     COMMENT '开始履约时间；NULL=未开始',
    delivered_at       DATETIME(3) NULL     COMMENT '交付时间；NULL=未交付',
    active_slot_id     BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('CONTRACT_ACTIVE','CONTRACT_STARTED','CONTRACT_DELIVERED') THEN slot_id END) VIRTUAL COMMENT '有效签约名额ID（生成列，终态为 NULL）',
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（只由库维护）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_engagement_contract_application (application_id),
    UNIQUE KEY uk_engagement_contract_active_slot (active_slot_id),
    KEY idx_engagement_contract_task_time (task_id, effective_at),
    KEY idx_engagement_contract_worker_time (worker_id, effective_at),
    CONSTRAINT ck_engagement_contract_status CHECK (status IN ('CONTRACT_ACTIVE','CONTRACT_STARTED','CONTRACT_DELIVERED','CONTRACT_COMPLETED','CONTRACT_CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='签约（一名额同时至多一份有效签约；取消后可重新录用并新建签约）';
