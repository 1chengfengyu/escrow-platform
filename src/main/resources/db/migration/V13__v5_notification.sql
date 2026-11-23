-- V5：站内信与死信落表。
--
-- 为什么站内信要落库而不是只发 Kafka：
--   1. 用户要能"查历史"，Kafka 不是查询存储；
--   2. 消费幂等靠 uk_notification_event 兜底，重复投递不会产生两条；
--   3. 死信单独落表，避免"消息进了 DLT 但没人知道"。

CREATE TABLE notification (
    id         BIGINT      NOT NULL COMMENT '雪花ID',
    event_id   VARCHAR(64) NOT NULL COMMENT '事件ID，消费幂等键',
    user_id    BIGINT      NOT NULL COMMENT '接收人账号ID',
    biz_type   VARCHAR(32) NOT NULL COMMENT '业务类型：PAYOUT / SETTLEMENT / DISPUTE',
    biz_no     VARCHAR(32) NOT NULL COMMENT '业务单号',
    title      VARCHAR(100) NOT NULL COMMENT '标题',
    content    VARCHAR(500) NOT NULL COMMENT '正文',
    read_flag  TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已读：0 未读 / 1 已读',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_event (event_id),
    KEY idx_notification_user (user_id, read_flag, created_at),
    CONSTRAINT ck_notification_read CHECK (read_flag IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内信';

CREATE TABLE notification_dead_letter (
    id            BIGINT      NOT NULL COMMENT '雪花ID',
    topic         VARCHAR(100) NOT NULL COMMENT '原主题',
    payload       TEXT        NOT NULL COMMENT '原始消息体',
    error_message VARCHAR(500) NULL    COMMENT '失败原因',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落表时间',
    PRIMARY KEY (id),
    KEY idx_notification_dlt_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知死信';
