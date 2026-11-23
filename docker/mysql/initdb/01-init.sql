-- 只在 mysql-data 卷为空时执行一次（MySQL 官方镜像 /docker-entrypoint-initdb.d 的约定）。
-- 改了这个文件必须 `docker compose down -v` 重建卷，否则不会重跑 —— 新手最容易踩的一条。
--
-- 故意不设 MYSQL_DATABASE：一次要建两个库，而 MYSQL_DATABASE 只建一个且会顺带授权，
-- 和下面的 GRANT 重复（docs/decisions.md #28）。
--   escrow       —— 开发与演示库
--   escrow_test  —— @SpringBootTest 专用，测试永远不许指向 escrow
--
-- 口令：dev_escrow 是本地演示口令，与 .env.example 的默认值一致；真实环境改 .env 的
-- DB_PASSWORD 并同时改这一行（SQL 读不到环境变量），改完 down -v 重建。

CREATE DATABASE IF NOT EXISTS `escrow`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `escrow_test`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'escrow'@'%' IDENTIFIED BY 'dev_escrow';
GRANT ALL PRIVILEGES ON `escrow`.*      TO 'escrow'@'%';
GRANT ALL PRIVILEGES ON `escrow_test`.* TO 'escrow'@'%';
FLUSH PRIVILEGES;