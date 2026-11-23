-- V5 收口验证（v5-c6）：不确定性处理 + 通知 + 迁移
-- 用法（在能连到测试库的机器上）：
--   mysql -h 127.0.0.1 -u <user> -p escrow_test < scripts/v5-verify.sql
-- 期望：第 1 段两行 success=1；第 2 段 diff=0；第 3~11 段均为空结果（0 行）。

-- 1. 迁移历史：V12（不确定性流水）与 V13（站内信）均成功
SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version IN ('12', '13')
ORDER BY installed_on;

-- 2. 六科目恒等式：ACC_ISSUED = 其余全部科目之和（与 FundLedgerGuard 同一口径）
SELECT issued.total AS issued,
       flow.total   AS flow,
       issued.total - flow.total AS diff
FROM (SELECT COALESCE(SUM(balance), 0) AS total FROM fund_account WHERE subject = 'ACC_ISSUED') issued
CROSS JOIN (SELECT COALESCE(SUM(balance), 0) AS total FROM fund_account WHERE subject <> 'ACC_ISSUED') flow
HAVING diff <> 0;

-- 3. 重复成功打款：同一结算单至多一条成功（uk_payout_success 的数据侧复核）
SELECT settlement_id, COUNT(*) AS success_count
FROM payout_order
WHERE status = 'PAYOUT_SUCCESS'
GROUP BY settlement_id
HAVING COUNT(*) > 1;

-- 4. 查证流水必须挂在真实打款单上，且请求号与打款单一致（逻辑外键）
SELECT v.id, v.payout_id, v.request_no
FROM payout_verification v
LEFT JOIN payout_order p ON p.id = v.payout_id
WHERE p.id IS NULL;

-- 5. 查证结论分布（人工核对用，不是错误）：应只有五种合法值
SELECT result, COUNT(*) AS records
FROM payout_verification
GROUP BY result
ORDER BY records DESC;

-- 6. 人工确认类处置必须落到成功打款，且实际金额与处置记录一致
SELECT d.payout_id, d.decision, d.actual_amount, p.status, p.actual_amount AS payout_actual
FROM payout_manual_decision d
JOIN payout_order p ON p.id = d.payout_id
WHERE d.decision IN ('CONFIRM_ACTUAL', 'ZERO_PAYMENT')
  AND (p.status <> 'PAYOUT_SUCCESS' OR p.actual_amount <> d.actual_amount);

-- 7. 转差异处置不得改动打款单状态：仍应停在待人工
SELECT d.payout_id, d.decision, p.status
FROM payout_manual_decision d
JOIN payout_order p ON p.id = d.payout_id
WHERE d.decision = 'TO_DIFFERENCE'
  AND p.status <> 'PAYOUT_MANUAL_REVIEW';

-- 8. 零扣款处置必须填写凭据说明
SELECT id, payout_id, decision
FROM payout_manual_decision
WHERE decision = 'ZERO_PAYMENT' AND (remark IS NULL OR TRIM(remark) = '');

-- 9. 站内信重复事件（uk_notification_event 的数据侧复核）
SELECT event_id, COUNT(*) AS copies
FROM notification
GROUP BY event_id
HAVING COUNT(*) > 1;

-- 10. 站内信字段完整性：正文为空或接收人为空说明装配有缺口
SELECT id, event_id, biz_no
FROM notification
WHERE user_id IS NULL OR content IS NULL OR content = '';

-- 11. 死信堆积：期望 0 行；有行说明消费连续失败，需要人工重放
SELECT id, topic, LEFT(payload, 200) AS payload_head, error_message, created_at
FROM notification_dead_letter
ORDER BY created_at DESC
LIMIT 20;
