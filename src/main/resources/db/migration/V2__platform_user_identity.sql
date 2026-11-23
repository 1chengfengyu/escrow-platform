-- V2：账号身份表。FR-ACCT.ROLE.01 要求同一账号可同时具备雇主与劳动者身份。
-- 口径：docs/decisions.md #18（表名 <域前缀>_<实体>）、#19（字段风格）、#20（枚举存大写名）。
-- 禁令：本文件不许出现 CREATE TABLE platform_user —— platform_user 由 V1__baseline.sql 于 V0 建好，
--       重复建表会触发 Flyway 校验和/表已存在错误，应用直接起不来。
-- 业务授权一律以本表为准；platform_user.role 只表示「注册时选择的默认身份」（docs/decisions.md #19 文末 V1 修订）。

CREATE TABLE platform_user_identity (
    id            BIGINT       NOT NULL COMMENT '雪花ID',
    user_id       BIGINT       NOT NULL COMMENT 'platform_user.id，逻辑外键，不建物理外键',
    identity_type VARCHAR(20)  NOT NULL COMMENT '身份类型：EMPLOYER / WORKER / ADMIN',
    status        VARCHAR(20)  NOT NULL COMMENT '身份状态：ACTIVE / DISABLED',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_identity (user_id, identity_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号身份（一人可多身份）';
