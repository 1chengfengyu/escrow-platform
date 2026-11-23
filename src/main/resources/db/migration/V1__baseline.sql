-- V1 基线：只建 platform_user 一张表。
-- 口径见 docs/decisions.md #18~#22；改字段必须先改文档再改这里。
--
-- 四条约定（违反会被打回）：
--   1. 主键 id 是雪花号，由 MyBatis-Plus 的 ASSIGN_ID 在应用侧生成，DDL 里禁止 AUTO_INCREMENT。
--   2. 不做逻辑删除（docs/decisions.md #15），所以没有 deleted 字段。
--   3. role / status 存大写枚举名，Java 侧用 @EnumValue（docs/decisions.md #20），不是数字码。
--   4. utf8mb4_0900_ai_ci 要求 MySQL 8.0+，本项目基线 8.0.42。

CREATE TABLE platform_user (
    id            BIGINT       NOT NULL COMMENT '雪花ID',
    phone         VARCHAR(20)  NULL     COMMENT '手机号，可为空但手机号与邮箱至少有一个',
    email         VARCHAR(128) NULL     COMMENT '邮箱，可为空',
    nickname      VARCHAR(64)  NOT NULL COMMENT '昵称，用于管理端查询',
    password_hash VARCHAR(100) NOT NULL COMMENT '口令哈希，BCrypt 为 60 字符，留余量',
    role          VARCHAR(20)  NOT NULL COMMENT '注册时选择的默认身份：EMPLOYER / WORKER / ADMIN',
    status        VARCHAR(20)  NOT NULL COMMENT '账号状态：ACTIVE / DISABLED / CLOSED',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    -- phone / email 可空：MySQL 唯一索引里多个 NULL 互不冲突，正好实现"可空但填了必须唯一"
    UNIQUE KEY uk_phone (phone),
    UNIQUE KEY uk_email (email),
    KEY idx_nickname (nickname),
    -- 8.0.16 起 CHECK 真正生效，拦住"既无手机也无邮箱"的脏账号
    CONSTRAINT ck_phone_or_email CHECK (phone IS NOT NULL OR email IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台账号';