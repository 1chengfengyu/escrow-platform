-- V3-c4：交付物（FR-DELIVER.SUBMIT.01~.03；SRS 4.2.2「交付物」/ A.3.1）
-- 一个签约至多一份交付物：UNIQUE(contract_id)
-- image_urls 存 JSON 数组文本（1~9 个 URL），由应用层序列化 / 反序列化，
-- 不在实体上引 Jackson TypeHandler（保持 domain 不依赖 Jackson）
-- 无物理外键、无 AUTO_INCREMENT、无逻辑删除、无金额列

CREATE TABLE engagement_deliverable (
    id           BIGINT       NOT NULL COMMENT '雪花ID',
    contract_id  BIGINT       NOT NULL COMMENT '签约ID（逻辑外键 engagement_contract.id）',
    worker_id    BIGINT       NOT NULL COMMENT '劳动者用户ID（逻辑外键 platform_user.id）',
    image_urls   VARCHAR(3000) NOT NULL COMMENT '图片URL列表（JSON数组文本，1~9个）',
    description  VARCHAR(500) NULL     COMMENT '文字说明，≤500字；空串按无说明处理',
    submitted_at DATETIME(3)  NOT NULL COMMENT '提交时间（验收倒计时起点）',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_engagement_deliverable_contract (contract_id),
    KEY idx_engagement_deliverable_worker_time (worker_id, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交付物（一个签约至多一份）';
