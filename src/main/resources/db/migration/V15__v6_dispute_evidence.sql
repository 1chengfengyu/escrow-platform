-- V6：争议全链路（FR-DISPUTE.EVIDENCE.01 / CLOSE.01）
-- 只新增：证据表 + 裁决依据列。不改 dispute 既有约束，向前兼容。

CREATE TABLE dispute_evidence (
    id             BIGINT        NOT NULL COMMENT '雪花ID',
    dispute_id     BIGINT        NOT NULL COMMENT '争议单ID，逻辑外键 dispute.id；不建物理外键',
    submitter_id   BIGINT        NOT NULL COMMENT '提交人账号ID，逻辑外键 platform_user.id',
    submitter_type VARCHAR(16)   NOT NULL COMMENT '提交方：EMPLOYER / WORKER',
    content        VARCHAR(1000) NULL     COMMENT '文字证据，1~1000 字；无文字时为空',
    image_urls     JSON          NULL     COMMENT '图片URL数组，≤9 张；无图片时为空',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时间',
    PRIMARY KEY (id),
    KEY idx_dispute_evidence_dispute (dispute_id, created_at),
    CONSTRAINT ck_dispute_evidence_submitter CHECK (submitter_type IN ('EMPLOYER','WORKER')),
    CONSTRAINT ck_dispute_evidence_content CHECK (
        content IS NULL OR CHAR_LENGTH(content) BETWEEN 1 AND 1000
    ),
    CONSTRAINT ck_dispute_evidence_media CHECK (content IS NOT NULL OR image_urls IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='争议证据';

-- 裁决依据（FR-DISPUTE.CLOSE.01）：可空，仅在 DISPUTE_RESOLVED 时有值。
-- 不加入 ck_dispute_resolved：约束已生效，新增可空列不影响旧数据。
ALTER TABLE dispute
    ADD COLUMN resolution_basis VARCHAR(500) NULL COMMENT '裁决依据，≤500 字' AFTER resolution;
