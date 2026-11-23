-- V3 收口验证（v3-c5）：迁移历史 + 超卖 / 重复交付物 / 名额一致性残留检查
-- 用法（在能连到测试库的机器上）：
--   mysql -h 127.0.0.1 -u <user> -p escrow_test < scripts/v3-verify.sql
-- 期望：第 1 段 V6~V10 全部 success=1；第 2~4 段均为空结果（0 行）。

-- 1. 迁移历史：V6~V10 连续成功（能回滚 = 上一版数据可直接升上来）
SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE CAST(version AS UNSIGNED) BETWEEN 6 AND 10
ORDER BY installed_rank;

-- 2. 超卖残留：同一任务的已占用名额数不得超过 slot_count
SELECT s.task_id, COUNT(*) AS occupied, t.slot_count
FROM task_slot s
JOIN task t ON t.id = s.task_id
WHERE s.status = 'SLOT_OCCUPIED'
GROUP BY s.task_id, t.slot_count
HAVING COUNT(*) > t.slot_count;

-- 3. 重复交付物：一个签约至多一份（UNIQUE(contract_id) 的数据侧复核）
SELECT contract_id, COUNT(*) AS copies
FROM engagement_deliverable
GROUP BY contract_id
HAVING COUNT(*) > 1;

-- 4. 名额与签约一致性：已占用 / 履约中 / 已交付名额必须回填签约 ID
SELECT id, task_id, status, engagement_id
FROM task_slot
WHERE status IN ('SLOT_OCCUPIED', 'SLOT_IN_PROGRESS', 'SLOT_DELIVERED')
  AND engagement_id IS NULL;
