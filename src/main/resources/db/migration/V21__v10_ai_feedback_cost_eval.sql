-- V10：反馈幂等、日成本汇总、评测结果（Judge 回填位）
-- 不建 ai_call_log：每次模型调用的明细是 ai_trace.stages_json 数组元素，
-- 粒度不同但同一次请求内，分两张表会导致 trace 回放跨表拼装（N1 裁决）。

-- 反馈：一次请求 + 一个用户只有一行，重复上报走 ON DUPLICATE KEY 更新，不累加。
CREATE TABLE ai_feedback
(
    id            BIGINT      NOT NULL COMMENT '雪花ID',
    trace_no      VARCHAR(32) NOT NULL COMMENT '关联 ai_trace.trace_no',
    user_id       BIGINT      NOT NULL COMMENT '上报用户ID',
    clicked       TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否点击结果',
    second_search TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '60 秒内是否二次搜索',
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_feedback (trace_no, user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'V10 检索反馈（在线代理指标来源）';

-- 日成本汇总：只含模型阶段；数值与 ai_trace 逐条归因之和一致。
CREATE TABLE ai_cost_daily
(
    id          BIGINT      NOT NULL COMMENT '雪花ID',
    stat_date   DATE        NOT NULL COMMENT '统计日期',
    phase       VARCHAR(16) NOT NULL COMMENT 'UNDERSTAND / RERANK / GENERATE',
    tokens      INT         NOT NULL DEFAULT 0 COMMENT '当日该阶段 token 合计',
    cost_micros BIGINT      NOT NULL DEFAULT 0 COMMENT '当日该阶段成本，百万分之一元',
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_cost_daily (stat_date, phase),
    CONSTRAINT ck_ai_cost_phase CHECK (phase IN ('UNDERSTAND', 'RERANK', 'GENERATE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'V10 AI 日成本汇总';

-- 评测结果：八项指标 + Judge 分数。judge_score 先建字段，无 key 时留 NULL。
-- metric_bp 用万分位整数存，避免 DECIMAL 与浮点在比较时漂移。
CREATE TABLE ai_eval_result
(
    id            BIGINT       NOT NULL COMMENT '雪花ID',
    run_no        VARCHAR(32)  NOT NULL COMMENT '评测批次号',
    dataset       VARCHAR(32)  NOT NULL COMMENT '数据集名，如 slot / ndcg / rag / reason',
    case_id       VARCHAR(64)  NOT NULL COMMENT '用例ID',
    metric        VARCHAR(32)  NOT NULL COMMENT '指标名',
    metric_bp     BIGINT       NULL COMMENT '指标值，万分位整数；不适用时为 NULL',
    judge_score   INT          NULL COMMENT 'LLM-as-Judge 1~5 分；无 key 时 NULL',
    prompt_version VARCHAR(64) NOT NULL COMMENT 'prompt 版本号',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_eval_result (run_no, dataset, case_id, metric),
    CONSTRAINT ck_ai_eval_judge CHECK (judge_score IS NULL OR judge_score BETWEEN 1 AND 5)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'V10 评测结果与 Judge 回填';
