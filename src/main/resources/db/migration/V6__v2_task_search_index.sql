-- V2-c3：任务检索复合索引（docs/decisions.md #48；FR-TASK.LIST.01 / .04）
-- 只加索引，不改列、不改约束、不建表；两列组合与排序白名单一一对应
-- idx_task_list_published：默认排序 + 招募开放入口
-- idx_task_list_price：单价排序入口

ALTER TABLE task
    ADD KEY idx_task_list_published (recruiting_closed, published_at, id),
    ADD KEY idx_task_list_price (recruiting_closed, unit_price, id);
