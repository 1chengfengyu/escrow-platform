-- v2-c6：V2 检索与资金查询的 EXPLAIN 脚本（每版完成定义第 1 条「能演示」）
--
-- 前置：task / task_slot / fund_escrow_order 表已由 Flyway 建好。
--       数据量越大效果越明显；空表也能看到 key，只是 rows 为 0。
-- 运行方式（任选其一）：
--   1) 用任意 MySQL 客户端打开本文件，逐条执行；
--   2) docker exec -i <mysql容器名> mysql -u<user> -p<pass> escrow < scripts/v2-explain.sql
--      （口令用环境变量或交互输入，不要写进本文件或 shell 历史）
--
-- 看三列：type（访问方式）、key（实际用的索引）、rows（预估扫描行数）。
-- 期望：1/2/6 走 ref/range + 对应索引；3 强制忽略索引后 type=ALL；4/5 是 const/ref 点查。

-- ---------------------------------------------------------------------------
-- 1) 列表默认排序：招募开放 + 发布时间倒序（FR-TASK.LIST.01/.03）
-- 期望：task 的 key=idx_task_list_published（recruiting_closed, published_at, id），
--       不该出现 Using filesort。
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT t.id, t.title, t.category, t.unit_price, t.published_at
  FROM task t
 WHERE t.published_at IS NOT NULL
   AND t.recruiting_closed = 0
   AND EXISTS (SELECT 1
                 FROM task_slot s
                WHERE s.task_id = t.id
                  AND s.status = 'SLOT_AVAILABLE')
 ORDER BY t.published_at DESC
 LIMIT 20;

-- ---------------------------------------------------------------------------
-- 2) 价格区间 + 单价排序（FR-TASK.LIST.02/.04）
-- 期望：key=idx_task_list_price（recruiting_closed, unit_price, id），type=range。
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT t.id, t.unit_price
  FROM task t
 WHERE t.published_at IS NOT NULL
   AND t.recruiting_closed = 0
   AND t.unit_price BETWEEN 3000 AND 8000
 ORDER BY t.unit_price ASC
 LIMIT 20;

-- ---------------------------------------------------------------------------
-- 3) 对照：强制忽略两个复合索引
-- 期望：type=ALL、key=NULL（全表扫 + filesort）。
--       与第 1 条对比即可说明「索引为什么值钱」。
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT t.id, t.published_at
  FROM task t IGNORE INDEX (idx_task_list_published, idx_task_list_price)
 WHERE t.published_at IS NOT NULL
   AND t.recruiting_closed = 0
 ORDER BY t.published_at DESC
 LIMIT 20;

-- ---------------------------------------------------------------------------
-- 4) 详情点查：主键 id（FR-TASK.DETAIL.01）
-- 期望：type=const、key=PRIMARY。
--       若该 id 在库中不存在，MySQL 直接输出 "no matching row in const table"，
--       同样说明走的是主键点查（实测 escrow_test 的 id=1 已被集成测试清掉，即此情形）。
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT t.id, t.title, t.published_at, t.cancelled_at
  FROM task t
 WHERE t.id = 1;

-- ---------------------------------------------------------------------------
-- 5) v2-c6 新增查询：详情投影托管金额（EscrowService.totalAmountOf）
-- 期望：type=const（或 ref）、key=uk_fund_escrow_order_task（task_id 唯一键），
--       证明一任务一托管单是库层保证的，不是应用层约定。
--       同样地，task_id=1 无对应托管单时会输出 "no matching row in const table"。
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT o.id, o.total_amount, o.status
  FROM fund_escrow_order o
 WHERE o.task_id = 1;

-- ---------------------------------------------------------------------------
-- 6) 到期扫描：已发布、未撤单、已过结束时间（FR-TASK.EXPIRE.01）
-- 期望：key=idx_task_end_time（V5 建在 task.end_time 上）。
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT t.id, t.end_time
  FROM task t
 WHERE t.published_at IS NOT NULL
   AND t.cancelled_at IS NULL
   AND t.end_time <= NOW();
