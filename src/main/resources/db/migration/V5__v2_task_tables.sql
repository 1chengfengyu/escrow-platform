-- V2-c2：任务与名额表（docs/decisions.md #47；SRS 3.2.1 / 4.2.2 / A.3.2）
-- 派生口径：task 不存 status / remaining_slots；名额在发布事务内生成（v2-c5）
-- 约束口径：无物理外键、无 AUTO_INCREMENT、无逻辑删除；金额不在本表

CREATE TABLE task (
    id                BIGINT        NOT NULL COMMENT '雪花ID',
    employer_id       BIGINT        NOT NULL COMMENT '雇主用户ID（逻辑外键 platform_user.id）',
    title             VARCHAR(60)   NOT NULL COMMENT '任务标题，1~60 字',
    description       VARCHAR(2000) NOT NULL COMMENT '任务描述，1~2000 字',
    category          VARCHAR(32)   NOT NULL COMMENT '任务类别，1~32 字（SRS 未给取值集，自由文本）',
    unit_price        BIGINT        NOT NULL COMMENT '单价，单位分',
    slot_count        INT           NOT NULL COMMENT '名额数，1~20',
    start_time        DATETIME(3)   NOT NULL COMMENT '开始时间',
    end_time          DATETIME(3)   NOT NULL COMMENT '结束时间',
    work_location     VARCHAR(128)  NOT NULL COMMENT '工作地点，1~128 字',
    settlement_note   VARCHAR(500)  NULL     COMMENT '结算说明，≤500 字',
    fee_rate          INT           NULL     COMMENT '费率快照，万分数；草稿为空，发布时写入',
    published_at      DATETIME(3)   NULL     COMMENT '发布时间；NULL=草稿（R0 判据）',
    cancelled_at      DATETIME(3)   NULL     COMMENT '撤单时间；NULL=未撤单',
    recruiting_closed TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '招募开关：0=开放 / 1=关闭（独立属性，非状态）',
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（只由库维护）',
    PRIMARY KEY (id),
    KEY idx_task_employer (employer_id),
    KEY idx_task_end_time (end_time),
    CONSTRAINT ck_task_unit_price   CHECK (unit_price > 0),
    CONSTRAINT ck_task_slot_count   CHECK (slot_count BETWEEN 1 AND 20),
    CONSTRAINT ck_task_time_range   CHECK (end_time > start_time),
    CONSTRAINT ck_task_title_len    CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 60),
    CONSTRAINT ck_task_desc_len     CHECK (CHAR_LENGTH(description) BETWEEN 1 AND 2000),
    CONSTRAINT ck_task_category_len CHECK (CHAR_LENGTH(category) BETWEEN 1 AND 32),
    CONSTRAINT ck_task_location_len CHECK (CHAR_LENGTH(work_location) BETWEEN 1 AND 128),
    CONSTRAINT ck_task_note_len     CHECK (settlement_note IS NULL OR CHAR_LENGTH(settlement_note) <= 500),
    CONSTRAINT ck_task_fee_rate     CHECK (fee_rate IS NULL OR fee_rate BETWEEN 0 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务（任务状态与剩余名额均为派生量，不落库，见 docs/decisions.md #47）';

CREATE TABLE task_slot (
    id             BIGINT      NOT NULL COMMENT '雪花ID',
    task_id        BIGINT      NOT NULL COMMENT '任务ID（逻辑外键 task.id）',
    seq_no         INT         NOT NULL COMMENT '名额序号，1~20',
    status         VARCHAR(32) NOT NULL COMMENT '名额状态：SLOT_AVAILABLE/SLOT_OCCUPIED/SLOT_IN_PROGRESS/SLOT_DELIVERED/SLOT_PENDING_ARBITRATION/SLOT_PENDING_SETTLEMENT/SLOT_COMPLETED/SLOT_REFUNDED/SLOT_RELEASED',
    application_id BIGINT      NULL     COMMENT '报名ID（逻辑外键，可空）',
    engagement_id  BIGINT      NULL     COMMENT '签约ID（逻辑外键，可空）',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（只由库维护）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_slot_task_seq (task_id, seq_no),
    KEY idx_task_slot_task_status (task_id, status),
    CONSTRAINT ck_task_slot_seq    CHECK (seq_no BETWEEN 1 AND 20),
    CONSTRAINT ck_task_slot_status CHECK (status IN ('SLOT_AVAILABLE','SLOT_OCCUPIED','SLOT_IN_PROGRESS','SLOT_DELIVERED','SLOT_PENDING_ARBITRATION','SLOT_PENDING_SETTLEMENT','SLOT_COMPLETED','SLOT_REFUNDED','SLOT_RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务名额（发布时按 slot_count 生成；不存冻结金额，金额真相源为 fund_escrow_detail.amount）';
