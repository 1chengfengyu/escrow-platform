-- V6：日终对账批次（V6-c1）
-- 只新增批次表；差异单沿用 V4 已建好的 settlement_difference。
-- 批次按日期幂等：uk_recon_batch_date 保证同一天只有一个批次，重跑走条件更新。

CREATE TABLE recon_batch (
    id            BIGINT       NOT NULL COMMENT '雪花 ID',
    recon_date    DATE         NOT NULL COMMENT '对账日期（按自然日）',
    status        VARCHAR(32)  NOT NULL COMMENT 'RECON_RUNNING / RECON_DONE / RECON_FAILED',
    local_count   INT          NOT NULL DEFAULT 0 COMMENT '本地打款尝试笔数',
    channel_count INT          NOT NULL DEFAULT 0 COMMENT '通道流水笔数',
    diff_count    INT          NOT NULL DEFAULT 0 COMMENT '差异单笔数',
    diff_amount   BIGINT       NOT NULL DEFAULT 0 COMMENT '差异金额绝对值合计（分）',
    identity_ok   TINYINT      NOT NULL DEFAULT 1 COMMENT '六科目恒等式是否平衡：1 平衡 / 0 不平衡',
    created_at    DATETIME     NOT NULL,
    finished_at   DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recon_batch_date (recon_date),
    CONSTRAINT ck_recon_batch_status CHECK (status IN ('RECON_RUNNING', 'RECON_DONE', 'RECON_FAILED')),
    CONSTRAINT ck_recon_batch_identity CHECK (identity_ok IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '日终对账批次';
