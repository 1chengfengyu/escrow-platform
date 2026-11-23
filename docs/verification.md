# 验证与测试

## 1. 总览

```
mvn test
Tests run: 1061, Failures: 0, Errors: 0, Skipped: 97
```

| 类别 | 数量 | 默认执行 | 说明 |
|---|---:|---|---|
| 单元测试 | — | 是 | 纯函数、状态机、校验器、脱敏、序列化 |
| Web 层契约测试 | — | 是 | `@WebMvcTest`，验证状态码、响应体、错误码、权限 |
| Schema 契约测试 | — | 是 | 表名、列名、索引、CHECK 约束与迁移脚本一致 |
| 集成测试 | 97 | 否（`ESCROW_IT=true` 才跑） | 真实 MySQL + Redis，覆盖事务、并发、幂等 |
| 前端类型检查 | 53 条路由 | 是 | `vue-tsc --noEmit` |
| 前端构建 | — | 是 | `vite build` |

97 条跳过的原因不是「坏了」，而是它们需要真实数据库。默认 `mvn test` 不设 `ESCROW_IT`，JUnit 的 `@EnabledIfEnvironmentVariable` 自动跳过；CI 的 `integration` job 会设变量并起 MySQL/Redis 真正执行。

## 2. 测试分布

`src/test/java` 共 158 个文件：

| 包 | 文件数 | 重点 |
|---|---:|---|
| `common` | 31 | 统一响应、异常映射、安全过滤链、限流、脱敏、缓存、审计 |
| `ai` | 18 | 槽位、检索、打分、重排守卫、引用校验、评测、成本 |
| `task` | 18 | 草稿校验、价格锁、派生状态、名额、检索、可见性 |
| `identity` | 15 | 注册、登录、锁定、双身份、资质、会话 |
| `settlement` | 14 | 结算金额、打款状态机、重试、对账、差异处理 |
| `engagement` | 12 | 报名并发、录用、合同、履约、验收、评价 |
| `dispute` | 7 | 立案、证据、仲裁、执行 |
| `fund` | 6 | 账户、托管、分录、余额不变量 |
| `admin` | 5 | 看板聚合、用户管理、资质审核 |
| 根包 | 32 | 跨域契约、迁移脚本、模块边界、启动上下文 |

## 3. 测试分层原则

1. **能纯函数测的，不启 Spring。** 状态机、金额计算、槽位解析、融合分都是纯函数，测试毫秒级完成。
2. **Controller 用切片测试。** `@WebMvcTest` 只加载 Web 层，Security 过滤器链显式引入，不碰数据库。
3. **Service 用 Mockito。** 跨域依赖全部替身，验证调用顺序、事务边界与异常映射。
4. **Schema 用契约测试。** 直接读 Flyway SQL，断言表名、列名、索引、CHECK 与实体字段一一对应。改表不改实体，测试先红。
5. **真库测试单独门控。** 事务、并发、幂等、行锁必须用真 MySQL 验证，H2 的语义差异会骗人。

## 4. 运行方式

### 默认测试

```bash
mvn test
```

不需要 MySQL/Redis。960 余条用例，约 1.5 分钟。

### 集成测试

```bash
docker compose --profile core up -d
$env:ESCROW_IT = "true"
$env:DB_HOST = "127.0.0.1"
$env:TEST_DB_NAME = "escrow_test"
mvn test
```

`escrow_test` 必须独立于 `escrow`。集成测试会清表、写数据、验证回滚，不能指向开发库。

### 单类运行

```bash
mvn test "-Dtest=V2EscrowFlowIntegrationTest"
mvn test "-Dtest=AiEvalTest"
```

### 前端

```bash
cd frontend
npm ci
npm run typecheck
npm run build
```

### 端到端

```bash
$env:ESCROW_DEMO_PASSWORD = "<本地演示口令>"
powershell -ExecutionPolicy Bypass -File scripts/verify-v12-e2e.ps1
```

脚本做 10 组检查：53 个路由组件存在、typecheck、build、`/api/ping`、注册、登录、`/api/identity/me`、任务分页契约、通知未读数、劳动者访问管理端返回 403。口令只从环境变量读取，不落明文。

## 5. CI

| job | 内容 | 状态 |
|---|---|---|
| `test` | `mvn test` + `npm ci` + `typecheck` + `build` | 必过 |
| `integration` | MySQL 8.0.42 + Redis 7.4 + `ESCROW_IT=true` + `mvn test` | `continue-on-error`，观察两周后转必过 |

`integration` 里 `DB_HOST=127.0.0.1` 是容器内回环，不是开发机的内网地址。MySQL service 自动创建 `escrow_test` 并授权给 `escrow`；Redis 用 `--requirepass` 启动。

## 6. AI 评测

| 测试 | 作用 | 是否联网 |
|---|---|---|
| `AiEvalTest` | 八项指标回归，固定语料 | 否 |
| `AiLiveEvalTest` | Judge 分数回填，写 `ai_eval_result` | 是，需要 key |
| `V10AiPersistenceIntegrationTest` | 轨迹、成本、评测结果的落库与幂等 | 是，需要 MySQL |

评测口径、指标定义与已知限制见 [ai-engineering.md](ai-engineering.md)。

## 7. 已完成的验收

- Flyway 23 个迁移在 MySQL 8.0.42 上从空库一次跑到最新版本。
- 发布 → 报名 → 录用 → 签约 → 履约 → 验收 → 结算 → 打款的主链路，在真库集成测试中跑通。
- 并发报名、并发录用、重复发布、重复打款均有集成测试覆盖。
- 97 条集成测试在本地 MySQL 8.0.42 + Redis 7.4 上通过。
- 前端 53 个路由组件全部通过类型检查与生产构建。
- AI stub/live 两种模式的 HTTP 验收脚本均 18/18 通过。
- 公开仓库前做过全历史敏感信息扫描与大对象扫描（见根目录提交记录与发布检查清单）。

## 8. 未验证项

- 多实例部署下的本地缓存一致性、分布式锁竞争、预算预检。
- 真实支付通道的签名、回调、退款时效。
- 大规模数据下的检索性能与索引选择性。
- 长时间运行的连接泄漏、定时任务漂移、消息积压。
- 前端浏览器兼容性只覆盖 Chrome/Edge 最近两个大版本。

这些限制在 [limitations.md](limitations.md) 中有对应说明。