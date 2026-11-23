-- V7 收口验证（v7-c5）：安全事件 / 审计流水 / 评价与站内信 / 迁移
-- 用法（在能连到测试库的机器上）：
--   mysql -h 127.0.0.1 -u <user> -p escrow_test < scripts/v7-verify.sql
-- 期望：第 1 段三行 success=1；第 2~11 段均为空结果（0 行）。

-- 1. 迁移历史：V16（安全事件）、V17（审计流水）、V18（评价）均成功
SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version IN ('16', '17', '18')
ORDER BY installed_on;

-- 2. 审计流水三要素不得为空：业务类型、业务单号、动作
SELECT id, biz_type, biz_no, action, created_at
FROM audit_log
WHERE biz_type IS NULL OR biz_type = ''
   OR biz_no IS NULL OR biz_no = ''
   OR action IS NULL OR action = ''
   OR created_at IS NULL;

-- 3. 审计操作人要么为空（系统任务），要么必须是真实账号
SELECT a.id, a.operator_id
FROM audit_log a
LEFT JOIN platform_user u ON u.id = a.operator_id
WHERE a.operator_id IS NOT NULL AND u.id IS NULL;

-- 4. 安全事件原因不得为空
SELECT id, method, path, reason, created_at
FROM security_event
WHERE reason IS NULL OR reason = '';

-- 5. 评价评分必须落在 1~5（CHECK 约束的数据侧复核）
SELECT id, rating
FROM engagement_review
WHERE rating < 1 OR rating > 5;

-- 6. 同一签约每人只能评一次（唯一索引的数据侧复核）
SELECT contract_id, reviewer_id, COUNT(*) AS reviews
FROM engagement_review
GROUP BY contract_id, reviewer_id
HAVING COUNT(*) > 1;

-- 7. 不允许自评
SELECT id, contract_id, reviewer_id, reviewee_id
FROM engagement_review
WHERE reviewer_id = reviewee_id;

-- 8. 评价只能挂在已完成的签约上
SELECT r.id, r.contract_id, c.status
FROM engagement_review r
JOIN engagement_contract c ON c.id = r.contract_id
WHERE c.status <> 'CONTRACT_COMPLETED';

-- 9. 站内信已读标记只能是 0 / 1
SELECT id, read_flag
FROM notification
WHERE read_flag NOT IN (0, 1);

-- 10. 站内信事件幂等键不得重复
SELECT event_id, COUNT(*) AS copies
FROM notification
GROUP BY event_id
HAVING COUNT(*) > 1;

-- 11. 账号状态只能是 ACTIVE / DISABLED / CLOSED
SELECT id, status
FROM platform_user
WHERE status NOT IN ('ACTIVE', 'DISABLED', 'CLOSED');
