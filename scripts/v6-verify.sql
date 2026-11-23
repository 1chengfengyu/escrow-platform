-- V6 收口验证（v6-c5）：对账批次 / 差异处置 / 争议证据 / 迁移
-- 用法（在能连到测试库的机器上）：
--   mysql -h 127.0.0.1 -u <user> -p escrow_test < scripts/v6-verify.sql
-- 期望：第 1 段两行 success=1；第 2 段 diff=0；第 3~12 段均为空结果（0 行）。

-- 1. 迁移历史：V14（对账批次）与 V15（争议证据）均成功
SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version IN ('14', '15')
ORDER BY installed_on;

-- 2. 六科目恒等式：ACC_ISSUED = 其余全部科目之和（与 FundLedgerGuard 同一口径）
SELECT issued.total AS issued,
       flow.total   AS flow,
       issued.total - flow.total AS diff
FROM (SELECT COALESCE(SUM(balance), 0) AS total FROM fund_account WHERE subject = 'ACC_ISSUED') issued
CROSS JOIN (SELECT COALESCE(SUM(balance), 0) AS total FROM fund_account WHERE subject <> 'ACC_ISSUED') flow
HAVING diff <> 0;

-- 3. 对账日唯一：同日只应有一个批次（uk_recon_batch_date 的数据侧复核）
SELECT recon_date, COUNT(*) AS batches
FROM recon_batch
GROUP BY recon_date
HAVING COUNT(*) > 1;

-- 4. 差异单状态与处置字段必须一致：已处理必须有结论与处置人，待处理不得有结论
SELECT id, status, resolution, resolver_id, resolved_at
FROM settlement_difference
WHERE (status = 'SETTLEMENT_DIFF_HANDLED'
        AND (resolution IS NULL OR resolver_id IS NULL OR resolved_at IS NULL))
   OR (status = 'SETTLEMENT_DIFF_PENDING'
        AND (resolution IS NOT NULL OR resolver_id IS NOT NULL OR resolved_at IS NOT NULL));

-- 5. 差异单金额口径：差额 = 本地 − 通道；负数允许，但两端都为 0 不应产生差异单
SELECT id, diff_type, local_amount, channel_amount
FROM settlement_difference
WHERE local_amount = 0 AND channel_amount = 0;

-- 6. 争议状态与裁决字段必须一致：已裁决必须有结论、依据、裁决人与时间
SELECT id, status, resolution, resolution_basis, resolver_id, resolved_at
FROM dispute
WHERE status = 'DISPUTE_RESOLVED'
  AND (resolution IS NULL OR resolution_basis IS NULL
       OR resolver_id IS NULL OR resolved_at IS NULL);

-- 7. 未裁决的争议不得提前带裁决字段
SELECT id, status, resolution, resolution_basis, resolver_id, resolved_at
FROM dispute
WHERE status <> 'DISPUTE_RESOLVED'
  AND (resolution IS NOT NULL OR resolution_basis IS NOT NULL
       OR resolver_id IS NOT NULL OR resolved_at IS NOT NULL);

-- 8. 同一名额不得同时存在两张待裁决争议（服务层 hasOpenDispute 的数据侧复核）
SELECT slot_id, COUNT(*) AS pending_disputes
FROM dispute
WHERE status = 'DISPUTE_PENDING'
GROUP BY slot_id
HAVING COUNT(*) > 1;

-- 9. 撤回次数不得越界（0~2，MAX_WITHDRAW_COUNT）
SELECT id, slot_id, withdraw_count
FROM dispute
WHERE withdraw_count < 0 OR withdraw_count > 2;

-- 10. 证据必须挂在真实争议单上（逻辑外键）
SELECT e.id, e.dispute_id
FROM dispute_evidence e
LEFT JOIN dispute d ON d.id = e.dispute_id
WHERE d.id IS NULL;

-- 11. 证据内容与图片不可同时为空（ck_dispute_evidence_media 的数据侧复核）
SELECT id, dispute_id, submitter_id
FROM dispute_evidence
WHERE content IS NULL AND image_urls IS NULL;

-- 12. 证据提交方只有两种合法值
SELECT submitter_type, COUNT(*) AS records
FROM dispute_evidence
GROUP BY submitter_type
ORDER BY records DESC;
