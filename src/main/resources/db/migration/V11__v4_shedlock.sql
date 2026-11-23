-- V11：ShedLock 分布式任务锁表（V4 引入）。
-- 口径：只用于 @Scheduled 的多实例互斥；不承载账务，也不代替业务幂等。
-- 建表语句由 ShedLock JdbcTemplateLockProvider 的官方 schema 简化而来（字段名 / 长度不可改）。

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL COMMENT '锁名，任务自定义',
    lock_until DATETIME(3)  NOT NULL COMMENT '锁到期时间',
    locked_at  DATETIME(3)  NOT NULL COMMENT '加锁时间',
    locked_by  VARCHAR(255) NOT NULL COMMENT '持锁实例标识',
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务分布式锁';
