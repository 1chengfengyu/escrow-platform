-- V10：服务者画像（AI 检索与理由的事实来源）与索引版本
-- 冷启动判据要求「新服务者派生字段为空、标 MISSING、接口不报错」，
-- 因此除 user_id / profile_version 外一律允许 NULL 或空串。
-- source_flags 记录每个字段的来源：ACTUAL / DERIVED / USER_INPUT / MISSING / FIXTURE。
CREATE TABLE ai_worker_profile
(
    id                  BIGINT       NOT NULL COMMENT '雪花ID',
    user_id             BIGINT       NOT NULL COMMENT '服务者用户ID',
    skills              VARCHAR(512) NOT NULL DEFAULT '' COMMENT '技能标签，英文逗号分隔，已清洗；允许空串',
    experience_years    INT          NULL COMMENT '从业年数，未知为 NULL',
    expected_unit_price BIGINT       NULL COMMENT '期望单价（分），未知为 NULL',
    work_location       VARCHAR(128) NULL COMMENT '可到岗城市，未知为 NULL',
    available_weekend   TINYINT(1)   NULL COMMENT '周末是否可接单，未知为 NULL',
    completed_orders    INT          NULL COMMENT '完单数，未知为 NULL',
    rating_x100         INT          NULL COMMENT '评分*100，未知为 NULL；禁止浮点',
    source_flags        JSON         NOT NULL COMMENT '字段来源映射，字段名 -> 来源枚举',
    profile_version     INT          NOT NULL DEFAULT 1 COMMENT '画像版本，更新即 +1',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_worker_profile_user (user_id),
    CONSTRAINT ck_ai_profile_exp CHECK (experience_years IS NULL OR experience_years >= 0),
    CONSTRAINT ck_ai_profile_price CHECK (expected_unit_price IS NULL OR expected_unit_price > 0),
    CONSTRAINT ck_ai_profile_rating CHECK (rating_x100 IS NULL OR rating_x100 BETWEEN 0 AND 500)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'V10 服务者画像';

CREATE TABLE ai_index_version
(
    id           BIGINT      NOT NULL COMMENT '雪花ID',
    version_no   INT         NOT NULL COMMENT '索引版本号，单调递增',
    status       VARCHAR(16) NOT NULL COMMENT 'BUILDING / ACTIVE / RETIRED',
    chunk_count  INT         NOT NULL DEFAULT 0 COMMENT '该版本收录的知识条目数',
    activated_at DATETIME    NULL COMMENT '转 ACTIVE 的时间',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_index_version_no (version_no),
    CONSTRAINT ck_ai_index_status CHECK (status IN ('BUILDING', 'ACTIVE', 'RETIRED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'V10 知识索引版本';

CREATE TABLE ai_index_stale_ref
(
    id          BIGINT       NOT NULL COMMENT '雪花ID',
    version_no  INT          NOT NULL COMMENT '失效引用所属版本',
    citation_id VARCHAR(64)  NOT NULL COMMENT '失效的引用ID',
    reason      VARCHAR(128) NOT NULL COMMENT '失效原因',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_stale_ref (version_no, citation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'V10 旧版本引用失效登记';
