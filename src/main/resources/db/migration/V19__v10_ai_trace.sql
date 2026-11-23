-- V10：AI 全链路 trace（c6 回放 + c7 灰度分流结果都落这张表）
-- 只新增，不修改 V1~V18。金额一律 BIGINT；枚举列 VARCHAR + CHECK。
-- 反馈（点击 / 二次搜索）单独放 ai_feedback（V21），用唯一键做幂等，
-- 避免同一请求被重复上报时把指标刷高。
CREATE TABLE ai_trace
(
    id           BIGINT       NOT NULL COMMENT '雪花ID',
    trace_no     VARCHAR(32)  NOT NULL COMMENT '链路号，直接复用 TraceIdFilter 写入 MDC 的 traceId',
    user_id      BIGINT       NOT NULL COMMENT '请求方用户ID（服务端会话注入，不取模型输出）',
    query_text   VARCHAR(200) NOT NULL COMMENT '原始查询（已脱敏、已截断）',
    strategy     VARCHAR(16)  NOT NULL COMMENT 'WEIGHTED / RERANK',
    bucket       INT          NOT NULL COMMENT 'userId 哈希桶号，0~9999',
    result_count INT          NOT NULL DEFAULT 0 COMMENT '返回条数，0 即零结果',
    stages_json  JSON         NOT NULL COMMENT '各阶段耗时/令牌/降级/每次模型调用明细',
    total_tokens INT          NOT NULL DEFAULT 0 COMMENT '本次请求总 token',
    cost_micros  BIGINT       NOT NULL DEFAULT 0 COMMENT '成本归因，单位百万分之一元',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_trace_no (trace_no),
    KEY idx_ai_trace_user_time (user_id, created_at),
    CONSTRAINT ck_ai_trace_strategy CHECK (strategy IN ('WEIGHTED', 'RERANK'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'V10 AI 链路 trace';
