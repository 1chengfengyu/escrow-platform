-- V7：审计流水（FR-AUDIT.MONEY.01 / FR-AUDIT.ADMIN.01）
-- 只追加不修改：应用侧不提供更新与删除入口。

CREATE TABLE audit_log (
    id           BIGINT       NOT NULL COMMENT '雪花ID',
    biz_type     VARCHAR(32)  NOT NULL COMMENT '业务类型：PAYOUT / SETTLEMENT / DISPUTE / RECON_DIFF / QUALIFICATION / USER',
    biz_no       VARCHAR(64)  NOT NULL COMMENT '业务单号或资源ID',
    action       VARCHAR(64)  NOT NULL COMMENT '动作：SUCCESS / FAIL / VERIFY / RESOLVE / DISABLE 等',
    from_status  VARCHAR(32)  NULL     COMMENT '变更前状态',
    to_status    VARCHAR(32)  NULL     COMMENT '变更后状态',
    operator_id  BIGINT       NULL     COMMENT '操作人账号ID；系统任务为空',
    operator_role VARCHAR(32) NULL     COMMENT '操作人身份',
    detail       VARCHAR(500) NULL     COMMENT '补充说明',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_audit_biz (biz_type, biz_no),
    KEY idx_audit_operator (operator_id, created_at),
    KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计流水（只追加）';
