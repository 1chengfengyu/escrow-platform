-- V8 死信重放留痕：让运维能在列表里直接看出「这条有没有重放过、重放了几次」
-- 只加两列，不改已有列；默认值保证存量行可读。
ALTER TABLE notification_dead_letter
    ADD COLUMN replay_count     INT         NOT NULL DEFAULT 0 COMMENT '累计重放次数' AFTER error_message,
    ADD COLUMN last_replayed_at DATETIME(3) NULL     COMMENT '最近一次重放时间' AFTER replay_count;
