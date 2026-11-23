# 架构决策记录

> 本文件是项目实施过程中形成的决策基线。源码与脚本注释中的 `docs/decisions.md #NN` 均指向本文对应条目。
> 只记录**已落地的决策**和**被否方案的理由**，不记录讨论过程。修改任何一条，必须先改本文，再改代码。

## 一、工程与构建

### #1 按业务域分包

`com.escrow.platform` 下按 `identity`、`task`、`engagement`、`fund`、`settlement`、`dispute`、`admin` 七个业务域分包，`common` 放跨域基础设施（统一响应、异常、安全、审计、通知、会话）。

每个域内部固定四层：`api`（Controller + 请求/响应 record）、`application`（Service、事务边界）、`domain`（实体、状态机、纯函数策略）、`infrastructure`（Mapper、外部适配器）。跨域只允许调用对方的 `application` 层 Service，不直接依赖对方的 Mapper 或实体。

不提前建空包。域首次落地时再建目录，避免仓库里出现一堆没有代码的空目录。

### #3 V0 不引入 Spring Security

V0 只验证「能启动、能连 MySQL/Redis、Flyway 能建表」。此时引入 Security 会把 `/actuator/health` 拦成 401，掩盖基础设施是否真正就绪。V1 实现账号域时再引入，并在同一次变更里配好 401/403 统一出口。

### #4 分页插件显式引入 jsqlparser

MyBatis-Plus 3.5.9 起把分页依赖拆成独立的 `mybatis-plus-jsqlparser`。只引 starter 时编译不报错，运行到分页查询才抛 `ClassNotFoundException`。因此显式引入，且版本必须与 starter 完全一致。

### #5 springdoc 延后到 V2

V1 的验收项包含「未登录访问管理端返回 401/403」。若此时开放 `/v3/api-docs` 与 `/swagger-ui/**`，会和验收路径冲突，也会把未完成的管理端接口暴露出来。V2 接口规模稳定后再引。

### #39 Lombok 使用边界

允许：`@Getter`、`@Setter`、`@RequiredArgsConstructor`、`@Slf4j`。

禁止：`@Data`、`@Builder`、`@AllArgsConstructor`、`@EqualsAndHashCode` 用在实体上。原因分别是：`@Data` 生成的 `equals/hashCode` 会破坏 MyBatis 一级缓存与实体身份语义，`toString` 会把口令哈希打进日志；`@Builder` 让 MyBatis 找不到无参构造；全参构造会让两个同类型参数的顺序错误变成编译期发现不了的 bug。

DTO、请求体、响应体一律用 Java 17 `record`，不用 Lombok。

### #40 测试依赖只引一个

只引 `spring-boot-starter-test`（test scope）。它已包含 JUnit 5、Mockito、AssertJ、JSONassert、Spring Test。单独引 JUnit 或 Mockito 会与 Boot BOM 的版本冲突，典型症状是 `NoSuchMethodError: org.junit.platform...`。

### #42 Mapper XML 位置

`mybatis-plus.mapper-locations=classpath*:/mapper/**/*.xml`。`classpath*:` 的星号必须有，否则打包成 jar 后只扫第一个匹配目录。XML 按域放在 `resources/mapper/<domain>/`，与 Java 包结构对齐。

没有真实 SQL 时不提前建空目录。

### #43 应用名与日志

`spring.application.name=escrow-platform`。日志 pattern 必须包含 `%X{traceId:-}`，其中 `:-` 不能省——MDC 没有值时输出空串而不是字面量 `traceId`。`TraceIdFilter` 在 `finally` 里 `MDC.clear()`，否则线程池复用会把上一个请求的 traceId 串到下一个请求。

### #44 启动类位置

`com.escrow.platform.EscrowPlatformApplication` 放在所有业务包的共同父包下，依靠 `@SpringBootApplication` 默认扫描子包。不写 `scanBasePackages`，避免包迁移后漏扫。

## 二、持久化与数据模型

### #14 主键与单据号

主键统一用雪花 ID（MyBatis-Plus `assign_id`），全局配置，实体上不写注解。

雪花 ID 是 19 位，超过 JavaScript `Number.MAX_SAFE_INTEGER`（16 位）。因此 Jackson 全局把 `Long` / `long` 序列化为 JSON 字符串。副作用是金额（以分存储的 `long`）也变成字符串，这是有意的：前端不应直接对金额做浮点运算。

`_id` 结尾的列一律是 `BIGINT` 主键或逻辑外键；`_no` 结尾的列一律是 `VARCHAR(32)` 业务单据号（全局唯一、生成后不变）。

不建物理 `FOREIGN KEY`。批量插入、幂等重放、将来拆库都会被外键约束限制；一致性由服务层保证，并用契约测试与校验脚本兜底。

### #15 不启用逻辑删除

不配 `logic-delete-field`，表里不加 `deleted` 列。三个理由：

1. 全局逻辑删除会污染每一条 SQL，包括手写 XML 和 `@Select`，排查成本极高。
2. 逻辑删除与唯一索引冲突：注销用户仍占用 `uk_phone`，无法用原手机号重新注册。
3. 资金系统不该有「删除」动作。有账的实体永远不物理删除，也不该用半吊子的逻辑删除，而应该用**状态**表达。

账号注销用 `status='CLOSED'`，历史流水、分录、对账记录仍可查，只是禁止登录。

### #16 Actuator 暴露范围

只暴露 `health` 和 `info`，绝不写 `*`。`/actuator/env` 会吐数据库口令，`/actuator/heapdump` 能下载整个堆内存。

`show-details` 在 V0 用 `never`（此时没有「已授权」语义）；V1 引入 Security 后改 `when-authorized`。开启 `probes.enabled`，保留 liveness/readiness 探针路径。

### #17 时区与字符集

JVM、JDBC、Jackson、MySQL 统一 `Asia/Shanghai`；库表统一 `utf8mb4` / `utf8mb4_0900_ai_ci`。时间列用 `DATETIME(3)`，毫秒精度——秒级精度不足以判定「打款执行中→超时」和先到先得的并发场景。

### #18 表名规范

全库表名 `<域前缀>_<实体>`，全小写蛇形，不加 `t_` / `tb_` 前缀。

| 域 | 前缀 |
|---|---|
| identity | `platform_` |
| task | `task_` |
| engagement | `engagement_` |
| fund | `fund_` |
| settlement | `settlement_` |
| dispute | `dispute_` |
| admin | `admin_` |

identity 用 `platform_` 而不是 `identity_`：`platform_user` 表达「平台账号」，且避免与 `platform_user_identity` 的语义打架。

### #19 platform_user 字段清单

九个字段：`id`、`phone`、`email`、`nickname`、`password_hash`、`role`、`status`、`created_at`、`updated_at`。

- `phone` / `email` 可空，但 `CHECK (phone IS NOT NULL OR email IS NOT NULL)` 阻止「既无手机也无邮箱」的脏账号。
- `uk_phone` / `uk_email` 唯一索引允许多个 NULL——MySQL 的唯一索引对 NULL 互不冲突，正好实现「可空但填了必须唯一」。
- `role` 只表示注册时选择的默认身份。V1 建 `platform_user_identity` 后，业务授权一律以身份表为准。
- `password_hash VARCHAR(100)`：BCrypt 固定 60 字符，留余量给 Argon2id（约 96 字符）。绝不能用 32——那是 MD5 时代的长度。
- 不加 `deleted`（见 #15），不加 `version`（非资金表，不需要乐观锁）。

### #20 枚举与状态列

`VARCHAR` 存大写枚举名，Java 侧 `enum` + `@EnumValue`，不用 MySQL `ENUM`，也不用 `TINYINT` 数字码。理由：看表自解释、加值不改表结构、MyBatis-Plus 原生支持。

不配全局 `default-enum-type-handler`，只使用 `@EnumValue`。全局 handler 会接管没有注解的枚举，行为不可预测。

V2 起新建表的状态列统一 `VARCHAR(32)`；`platform_user.role` / `status` 保持 `VARCHAR(20)`。状态名与错误码不得超过 28 字符，超了先改本条再改表。

### #21 password_hash 长度

`VARCHAR(100)`。BCrypt 输出固定 60 字符；Argon2id 的 PHC 字符串约 96 字符。用 `VARCHAR(32)` 说明设计者还停留在 MD5 时代。

### #22 不写业务种子数据

仓库不提交任何业务种子数据。测试语料只放 `src/test/resources/ai/`，画像来源标 `FIXTURE`。生产与演示数据由使用者在本地自行创建，不随仓库分发。

## 三、接口契约

### #29 统一响应体

```json
{ "code": "SUCCESS", "message": "ok", "data": {}, "traceId": "3f8a2c1e9b4d47a6" }
```

- `code` 是字符串，不是数字。SRS 的错误码本身就是字符串常量，用数字要维护双向映射表。
- 不加 `success`：它与 `code` 表达同一信息，不一致时必出 bug。
- 不加 `timestamp`：日志里有时间，`traceId` 才是能串起链路的字段。
- `traceId` 必须有值。`TraceIdFilter` 读 `X-Trace-Id`，没有则生成 32 位 UUID，写入 MDC 与响应头。

### #30 错误码

`common/error/ErrorCode` 用 enum。协议级九个：`SUCCESS`、`VALIDATION_FAILED`、`UNAUTHENTICATED`、`FORBIDDEN`、`RESOURCE_NOT_FOUND`、`METHOD_NOT_ALLOWED`、`CONFLICT`、`INTERNAL_ERROR`、`DEPENDENCY_UNAVAILABLE`。

业务错误码三条铁律：

1. 名字与 SRS 逐字一致，不改写、不翻译。
2. 一个业务场景一个码，不复用。`TASK_HAS_ACTIVE_SLOT` 与 `TASK_NOT_CANCELABLE`、`INSUFFICIENT_BALANCE` 与 `CONCURRENT_MODIFICATION` 必须分开。
3. 不用 `CONFLICT` / `INTERNAL_ERROR` 兜底业务失败。前端需要靠错误码区分「可重试」与「不可重试」。

`VALIDATION_FAILED` 只用于请求体无法解析、参数类型错误、框架级校验失败；业务字段校验用 `INVALID_PARAMETER`，并在 `data.fields` 指明出错字段。

### #31 HTTP 状态码策略

按 HTTP 语义返回，不做「永远 200」。

| 结果 | HTTP | body.code |
|---|---|---|
| 成功 | 200 / 201 | `SUCCESS` |
| 框架级参数错误 | 400 | `VALIDATION_FAILED` |
| 业务字段不合法 | 400 | `INVALID_PARAMETER` |
| 未认证 | 401 | `UNAUTHENTICATED` |
| 无权限 / 身份不可用 | 403 | `FORBIDDEN` / `IDENTITY_NOT_AVAILABLE` |
| 不存在 | 404 | `RESOURCE_NOT_FOUND` |
| 冲突 / 已存在 | 409 | 具体业务码 / `ACCOUNT_EXISTS` |
| 账号锁定 | 423 | `ACCOUNT_LOCKED` |
| 限流 | 429 + `Retry-After` | `RATE_LIMITED` |
| 服务错误 | 500 | `INTERNAL_ERROR` |

「永远 200」会让监控、告警、客户端重试全部失效：余额不足与通道超时长得一模一样，而前者绝不能重试。

### #32 /api/ping 的三条通路

| 接口 | 触发 | 期望 |
|---|---|---|
| `GET /api/ping` | — | 200 + `SUCCESS` |
| `GET /api/ping/echo?name=` | `@NotBlank` 失败 | 400 + `VALIDATION_FAILED` |
| `GET /api/ping/boom` | 主动抛异常，仅 `dev` profile | 500 + `INTERNAL_ERROR`，响应体不含堆栈 |

404 必须被统一响应体接管。Boot 3.2+ 静态资源未命中抛 `NoResourceFoundException`，要与 `NoHandlerFoundException` 一起处理，否则 404 返回 Whitelabel JSON，格式与其它响应不一致。

### #33 测试切片不加载数据源

`@WebMvcTest`、纯单元测试不启动完整上下文，不加载数据源。需要 Spring 上下文但不碰数据库的测试，用 `@SpringBootTest` + 排除数据源自动配置，或直接用 Mockito 构造 Service。只有 `ESCROW_IT=true` 的集成测试才连真库。

## 四、基础设施

### #26 Docker 持久化用 named volume

MySQL、Redis、MongoDB 的数据卷用 named volume，不用 Windows 目录 bind mount。Windows 共享目录的权限与 I/O 性能都会让数据库不稳定。

### #27 端口绑定

本地开发默认绑 `0.0.0.0`，便于宿主机与容器互访。共享环境改为 `127.0.0.1:3306:3306` 并通过 SSH 隧道访问；生产环境不直接暴露数据库端口。

### #28 建库脚本

`docker/mysql/initdb/01-init.sql` 只做三件事：建 `escrow` 与 `escrow_test` 两个库、建应用账号、授权。不建表、不写业务数据——表结构全部由 Flyway 管理。

`escrow_test` 必须独立于 `escrow`，集成测试永远不指向开发库。

## 五、任务域

### #47 task / task_slot 落库口径

`task` 表**不建** `status` 列，也**不建** `remaining_slots` 列。任务状态由 `f(名额状态集合, 招募开关, 撤单标志)` 实时派生（优先级 R0–R12），剩余名额由名额集合实时计算。两者都是展示量，落库就是第二真相源。

草稿事实用 `published_at IS NULL` 表示；撤单用 `cancelled_at`；`recruiting_closed` 是独立属性（撤单或到期），与进度状态正交。

名额在**发布事务内**生成，草稿阶段没有 `task_slot` 行。`task_slot` 不冗余冻结金额——金额唯一真相源是 `fund_escrow_detail.amount`，任务域需要金额时调 fund 域 Service。

服务费一律 `Math.floorDiv(payable * rate, 10000)`，禁止 `Math.round`、禁止先转 `double`，与数据库 `CHECK (fee = FLOOR(payable * rate / 10000))` 对齐。

### #48 任务列表检索口径

五个筛选条件：关键词、类别、报酬区间、时间范围、分页。

- 关键词：`title LIKE %kw% OR description LIKE %kw%`，trim 后为空视为不筛。
- 类别：等值匹配，自由文本 ≤32 字。
- 报酬：`unit_price` 单边或双边区间，单位分；`min > max` 返回 400。
- 时间：任务时间窗与查询区间**有交集**（`start_time <= to AND end_time >= from`，含端点）。
- 排序：默认按发布时间倒序，可选单价排序。

索引只建两条：`(recruiting_closed, published_at, id)` 与 `(recruiting_closed, unit_price, id)`。不为 `category`、`work_location`、`title` 单独建索引：低选择性列与 `LIKE '%x%'` 都用不上索引，多建只会拖慢写入。

详情可见性：草稿对非本人返回 404（不暴露存在性）；已发布任务对任何已登录用户可读；非当事人视图不含托管金额、雇主余额、结算明细、名额明细。

### #49 任务详情三级缓存

读路径固定 `Caffeine → Redis → DB`。

- Caffeine：`taskId` 为键，`maximumSize=1000`，TTL 60s + 抖动。
- Redis：`task:detail:{taskId}`，TTL 30min + 抖动。
- 空值标记只写 Redis，TTL 60s + 抖动。

缓存对象是 `TaskDetailSnapshot`（与观察者无关的展示字段 + 名额计数），**不缓存** `TaskDetailView`。后者是否包含名额明细取决于当事人身份，按 taskId 缓存会导致越权。

写路径：事务提交后 `evict` 两层，不做延迟双删，不用 `@Cacheable` / `@CacheEvict`。`evict` 必须能清掉空值标记，否则「查询不存在 → 创建 → 再查」会在 60s 内继续 404。

缓存异常一律 catch 成 warn 日志后回源，缓存故障不得让详情接口返回 5xx。

### #51 治理看板

`admin` 域的治理看板不在 SRS 的 FR 清单内，属于运营支撑功能。它只读聚合：用户与身份数量、任务与名额状态分布、托管与结算余额、争议与超时单量、近期审计事件。看板不提供任何写操作，所有写操作仍走各自的领域接口并留审计。

### #52 复式记账与唯一记账入口

资金变动一律走 `JournalService`，按复式记账写 `fund_journal_batch`（批次）与 `fund_journal_entry`（分录）。同一批次内借贷必平，批次号全局唯一，分录只追加不修改。

业务代码不得直接更新余额列。余额是分录的派生结果，由记账服务在同一事务内更新账户余额，并用 `FundLedgerGuard` 校验「可用 + 冻结 = 总额」等不变量。

## 六、技术选型偏离

### D-04 应用与中间件的部署拓扑

应用进程跑在开发机，MySQL / Redis / Kafka / MongoDB 跑在 Docker Compose。这样应用可以直接用 IDE 调试，中间件用容器保证版本一致。生产部署时应用也容器化，拓扑差异由环境变量吸收。

### D-06 本地缓存的多实例陈旧

Caffeine 本地层在单实例部署下没有一致性问题。多实例部署时，其它实例的驱逐不会让本机缓存失效，最长陈旧 60s。当前版本按单实例交付；多实例化时需要引入广播驱逐或去掉本地层。

### D-07 撤单 / 到期后的托管金额展示

任务撤单或到期后，详情页仍显示**原托管金额**，而不是退款后的剩余额。这是展示口径的选择：原托管额是历史事实，剩余额是资金域的内部状态。两者都不影响账务正确性。

### D-08 已知代价汇总

- 关键词检索用 `LIKE '%kw%'`，数据量上来后需要全文索引或搜索引擎。
- 任务详情缓存为进程内 + Redis 两级，跨实例一致性依赖 TTL。
- 集成测试需要真实 MySQL/Redis，默认跳过，由 CI 的 integration job 单独执行。