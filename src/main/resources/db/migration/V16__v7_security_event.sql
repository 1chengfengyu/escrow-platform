-- V7：越权与安全事件（NFR-SEC.08 / FR-SEC.ALERT.01）
-- 只新增一张表，不改既有表；事件由应用写入，只追加不修改。

CREATE TABLE security_event (
    id          BIGINT       NOT NULL COMMENT '雪花ID',
    user_id     BIGINT       NULL     COMMENT '触发事件的账号ID；未登录时为空',
    role        VARCHAR(32)  NULL     COMMENT '触发时的默认角色',
    method      VARCHAR(10)  NOT NULL COMMENT 'HTTP 方法；非 HTTP 场景为 UNKNOWN',
    path        VARCHAR(255) NOT NULL COMMENT '请求路径或操作标识',
    reason      VARCHAR(500) NOT NULL COMMENT '拒绝原因',
    target_type VARCHAR(32)  NULL     COMMENT '目标资源类型',
    target_id   BIGINT       NULL     COMMENT '目标资源ID',
    ip          VARCHAR(64)  NULL     COMMENT '来源IP，取 X-Forwarded-For 首段',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_security_event_user (user_id, created_at),
    KEY idx_security_event_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='安全事件（越权告警口径）';
