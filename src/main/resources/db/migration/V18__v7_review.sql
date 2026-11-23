-- V7-c4：签约评价（FR-SIGN.REVIEW.01 / FR-REPUTATION.01）。
--
-- 一次签约每人只能评一次：UNIQUE(contract_id, reviewer_id)。
-- 被评价人单独落列：信誉页按 reviewee_id 聚合，避免回查跨域任务表拿雇主。
-- 无物理外键、无 AUTO_INCREMENT、无逻辑删除；评分 1~5 由 CHECK 兜底。

CREATE TABLE engagement_review (
    id          BIGINT       NOT NULL COMMENT '雪花ID',
    contract_id BIGINT       NOT NULL COMMENT '签约ID（逻辑外键 engagement_contract.id）',
    reviewer_id BIGINT       NOT NULL COMMENT '评价人账号ID（逻辑外键 platform_user.id）',
    reviewee_id BIGINT       NOT NULL COMMENT '被评价人账号ID（逻辑外键 platform_user.id）',
    rating      TINYINT      NOT NULL COMMENT '评分 1~5',
    comment     VARCHAR(200) NULL     COMMENT '文字评价，可空',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_engagement_review_contract_reviewer (contract_id, reviewer_id),
    KEY idx_engagement_review_reviewee_time (reviewee_id, created_at),
    CONSTRAINT ck_engagement_review_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='签约评价（一份签约每人一次）';
