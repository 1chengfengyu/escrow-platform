-- V3-c1：报名表（FR-SIGN.APPLY.01~.07；SRS 3.3 / 4.2.2 D3 / A.3.7）
-- 有效报名唯一性：MySQL 无部分唯一索引，用生成列 active_task_id / active_worker_id 实现
-- 状态白名单与 ApplicationStatus 枚举逐字一致；无物理外键、无 AUTO_INCREMENT、无逻辑删除
-- 报名不占名额：本表不写 task_slot.application_id（FR-SIGN.APPLY.04）

CREATE TABLE engagement_application (
    id               BIGINT      NOT NULL COMMENT '雪花ID',
    task_id          BIGINT      NOT NULL COMMENT '任务ID（逻辑外键 task.id）',
    worker_id        BIGINT      NOT NULL COMMENT '劳动者用户ID（逻辑外键 platform_user.id）',
    status           VARCHAR(32) NOT NULL COMMENT '报名状态：APPLY_PENDING/APPLY_HIRED/APPLY_REJECTED/APPLY_WITHDRAWN',
    applied_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '报名时间',
    withdrawn_at     DATETIME(3) NULL     COMMENT '撤回时间；NULL=未撤回',
    active_task_id   BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('APPLY_PENDING','APPLY_HIRED') THEN task_id END) VIRTUAL COMMENT '有效报名任务ID（生成列，终态为 NULL）',
    active_worker_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('APPLY_PENDING','APPLY_HIRED') THEN worker_id END) VIRTUAL COMMENT '有效报名劳动者ID（生成列，终态为 NULL）',
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（只由库维护）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_engagement_application_active (active_task_id, active_worker_id),
    KEY idx_engagement_application_task_time (task_id, applied_at),
    KEY idx_engagement_application_worker_time (worker_id, applied_at),
    CONSTRAINT ck_engagement_application_status CHECK (status IN ('APPLY_PENDING','APPLY_HIRED','APPLY_REJECTED','APPLY_WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报名（同一任务+劳动者至多一条非终态记录；报名不占名额）';
