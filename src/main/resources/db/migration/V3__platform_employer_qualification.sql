-- V3：雇主资质表（FR-ACCT.QUAL.01~QUAL.03、FR-ADMIN.QUAL.01，字段清单见 SRS 4.2.2「雇主资质」）。
-- 口径：docs/decisions.md #18（表名 <域前缀>_<实体>）、#20（枚举存大写名）、#30（业务码不新增）。
--
-- 三个刻意的选择：
--   1. 1:1 用户（uk_employer_qual_user）：重复提交走 UPDATE，不插第二行。
--   2. UNSUBMITTED 不落库：没提交就是「没有这一行」，对外接口把无记录翻译成 UNSUBMITTED；
--      若把 UNSUBMITTED 也存进来，「没有记录」与「有记录但从未提交」会变成两个真相源。
--   3. idx_employer_qual_status 服务于管理端「待审核列表」：按状态筛选 + 按提交时间升序，缺索引会全表扫。
--
-- 禁令：本文件不建 platform_worker_profile（SRS 无独立 FR，留 V2），不动 platform_user /
--       platform_user_identity，不建物理外键，不加 deleted / version（本表不是资金表）。

CREATE TABLE platform_employer_qualification (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    user_id        BIGINT       NOT NULL COMMENT '账号ID，1:1 用户',
    subject_type   VARCHAR(20)  NOT NULL COMMENT '主体类型：INDIVIDUAL / SOLE_PROPRIETOR',
    subject_name   VARCHAR(128) NOT NULL COMMENT '主体名称',
    id_last4       CHAR(4)      NOT NULL COMMENT '证件号后4位',
    review_status  VARCHAR(20)  NOT NULL COMMENT '审核状态：PENDING / APPROVED / REJECTED（未提交不落库）',
    reviewer_id    BIGINT       NULL     COMMENT '审核人账号ID，未审核为空',
    review_reason  VARCHAR(255) NULL     COMMENT '驳回理由 / 审核备注',
    submitted_at   DATETIME(3)  NULL     COMMENT '最近一次提交时间',
    reviewed_at    DATETIME(3)  NULL     COMMENT '最近一次审核时间',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_employer_qual_user (user_id),
    KEY idx_employer_qual_status (review_status, submitted_at),
    CONSTRAINT ck_employer_qual_id_last4 CHECK (CHAR_LENGTH(id_last4) = 4),
    CONSTRAINT ck_employer_qual_status CHECK (review_status IN ('PENDING','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雇主资质';
