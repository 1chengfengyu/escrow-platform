-- V4 收口验证（v4-c6）：恒等式 + 重复结算 / 重复成功打款 + 金额口径 + 状态一致性
-- 用法（在能连到测试库的机器上）：
--   mysql -h 127.0.0.1 -u <user> -p escrow_test < scripts/v4-verify.sql
-- 期望：第 1 段 V11 一行 success=1；第 2 段恒等式 diff=0；第 3~9 段均为空结果（0 行）。

-- 1. 迁移历史：V11（shedlock）成功
SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version = '11';

-- 2. 六科目恒等式：ACC_ISSUED = 其余全部科目之和（与 FundLedgerGuard 同一口径）
SELECT issued.total AS issued,
       flow.total   AS flow,
       issued.total - flow.total AS diff
FROM (SELECT COALESCE(SUM(balance), 0) AS total FROM fund_account WHERE subject = 'ACC_ISSUED') issued
CROSS JOIN (SELECT COALESCE(SUM(balance), 0) AS total FROM fund_account WHERE subject <> 'ACC_ISSUED') flow
HAVING diff <> 0;

-- 3. 重复结算：一个名额至多一张结算单（uk_settlement_slot 的数据侧复核）
SELECT slot_id, COUNT(*) AS copies
FROM settlement
GROUP BY slot_id
HAVING COUNT(*) > 1;

-- 4. 重复成功打款：同一结算单至多一条成功（uk_payout_success 的数据侧复核）
SELECT settlement_id, COUNT(*) AS success_count
FROM payout_order
WHERE status = 'PAYOUT_SUCCESS'
GROUP BY settlement_id
HAVING COUNT(*) > 1;

-- 5. 金额口径：fee = FLOOR(payable × fee_rate / 10000)，net = payable − fee
SELECT settlement_no, payable_amount, fee_rate, fee_amount, net_amount
FROM settlement
WHERE fee_amount <> FLOOR(payable_amount * fee_rate / 10000)
   OR net_amount <> payable_amount - fee_amount;

-- 6. 结算与打款金额不一致：请求金额必须等于结算实付
SELECT s.settlement_no, s.net_amount, p.request_no, p.amount
FROM settlement s
JOIN payout_order p ON p.settlement_id = s.id
WHERE p.amount <> s.net_amount;

-- 7. 状态不一致：成功打款 ↔ 结算完成；结算完成（实付 > 0）↔ 有成功打款
SELECT s.settlement_no, s.status AS settlement_status, p.status AS payout_status
FROM settlement s
JOIN payout_order p ON p.settlement_id = s.id
WHERE (p.status = 'PAYOUT_SUCCESS' AND s.status <> 'SETTLEMENT_COMPLETED')
   OR (s.status = 'SETTLEMENT_COMPLETED' AND s.net_amount > 0 AND p.status <> 'PAYOUT_SUCCESS');

-- 8. 空闲打款：结算已完成（实付 = 0）不应有任何打款单
SELECT s.settlement_no, p.request_no
FROM settlement s
JOIN payout_order p ON p.settlement_id = s.id
WHERE s.status = 'SETTLEMENT_COMPLETED' AND s.net_amount = 0;

-- 9. 凭证批次借贷不平衡（任一批次借方合计 ≠ 贷方合计）
SELECT b.id, b.biz_type, b.biz_no,
       COALESCE(SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END), 0) AS debit,
       COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END), 0) AS credit
FROM fund_journal_batch b
JOIN fund_journal_entry e ON e.journal_batch_id = b.id
GROUP BY b.id, b.biz_type, b.biz_no
HAVING debit <> credit;
