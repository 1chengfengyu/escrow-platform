# 技术栈与版本锁定

> 版本事实以 `pom.xml`、`frontend/package.json`、`docker-compose.yml` 为准。
> 本文件只回答两个问题：**为什么锁这些版本**，以及**哪些技术评估过但没有采用**。

---

# 一、后端

| 技术 | 版本 | 版本来源 | 用途 |
|---|---|---|---|
| Java | 17 | `maven.compiler.release` | 语言级别 |
| Spring Boot | 3.5.16 | `spring-boot-starter-parent` | 应用框架 |
| Spring Framework | 6.2.x | Boot BOM | 由 Boot 决定，不手写 |
| MyBatis-Plus | 3.5.18 | 手写 | ORM 与条件更新 |
| mybatis-plus-jsqlparser | 3.5.18 | 手写 | 分页拦截器，必须与 starter 同版本 |
| HikariCP | Boot BOM | Boot BOM | 连接池 |
| MySQL | 8.0.42 | `docker-compose.yml` | 主库，utf8mb4 |
| Flyway | Boot BOM | Boot BOM + `flyway-mysql` | 表结构迁移，共 23 个版本 |
| Redis | 7.4-alpine | `docker-compose.yml` | 会话、限流、缓存、分布式锁 |
| Lettuce | Boot BOM | Boot BOM | Redis 客户端 |
| Caffeine | Boot BOM | Boot BOM | 进程内缓存（任务详情第一级） |
| Spring Session Data Redis | Boot BOM | Boot BOM | 会话共享 |
| Kafka | 3.9.1（KRaft） | `docker-compose.yml` | 站内信投递与死信管理 |
| MongoDB | 7 | `docker-compose.yml` | 对账报文归档 |
| Nacos Server / nacos-client | 3.0.3 | SCA BOM | 仅用于打款参数动态刷新，未做注册发现 |
| ShedLock | 6.9.0 | 手写 | 多实例下定时任务互斥 |
| Spring AI | 1.1.8 | `spring-ai-bom` | OpenAI 兼容协议接入（DeepSeek） |
| Lombok | Boot BOM | Boot BOM（`optional`） | 只许 `@Getter` / `@Setter` / `@RequiredArgsConstructor` / `@Slf4j` |
| JUnit 5 / Mockito / AssertJ / MockMvc | Boot BOM | Boot BOM | 测试 |

# 二、前端

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5 | 框架 |
| TypeScript | 5.9 | 类型检查（`vue-tsc --noEmit`） |
| Vite | 7 | 构建与开发服务器 |
| Pinia | 3 | 状态管理 |
| Vue Router | 4 | 路由，53 个页面 |
| Naive UI | 2.45 | 组件库 |
| Tailwind CSS | 4 | 原子化样式 |
| ECharts | 6 | 治理看板图表 |
| Axios | 1.21 | HTTP 客户端 |

# 三、版本由谁管理

| 管理方 | 负责的技术 | 规则 |
|---|---|---|
| **Boot BOM** | Spring Framework、Jackson 2、HikariCP、Lettuce、Flyway、JUnit、Mockito、AssertJ、Spring Kafka、Spring Data MongoDB | `pom.xml` 里**不写版本号** |
| **SCA BOM** | nacos-client | 只引 BOM，不写单组件版本 |
| **手写锁定** | MyBatis-Plus、`mybatis-plus-jsqlparser`、ShedLock | 必须写明确版本 |
| **lockfile** | 前端全部依赖 | 以 `package-lock.json` 为准，CI 用 `npm ci` |
| **镜像 tag** | MySQL、Redis、Kafka、Nacos、MongoDB | 写死小版本，不用 `latest` |

> 单独给 `mybatis-plus-jsqlparser` 写一个与 starter 不同的版本，会在运行期直接 `NoSuchMethodError`。两者版本必须完全一致。

# 四、评估过但没有采用

这些不是"以后再做"，而是在当前规模下权衡后主动放弃的选项。写在这里是为了避免后来者重复论证。

| 技术 | 结论 | 理由 |
|---|---|---|
| Seata | 未采用 | 单体单库，资金一致性由本地事务 + 复式记账 + 条件更新保证；引入分布式事务只增加故障面，不解决本项目的问题 |
| Dubbo | 未采用 | 跨域调用是进程内方法调用（只调对方 `application` 层），没有独立部署与独立扩容需求 |
| Sentinel | 未采用 | 限流点只有登录、注册、打款等少数接口，Redis 计数器 + `Retry-After` 已覆盖；接入控制台的运维成本高于收益 |
| Spring Cloud Gateway | 未采用 | 前后端同域部署，静态资源由 Nginx 或 Vite 预览托管，不需要网关层 |
| SkyWalking / Arthas | 未采用 | 可观测性用 Actuator + 结构化日志 + 全链路 `traceId`；单机排障用日志与断点足够 |
| Kubernetes | 未采用 | 单机 Docker Compose 已能完整复现环境；K8s 对演示与验收没有增量价值 |
| Elasticsearch | 未采用 | 检索规模是万级任务，MySQL `LIKE` + 中文二元切分 + 字段加权已满足；引入独立检索服务会带来数据同步问题 |
| 向量数据库 | 未采用 | 10 人 fixture 的语义召回没有可解释收益，反而让评测口径变模糊；当前用词法检索 + 确定性融合分做基线，模型重排作为可选增强 |
| 多模块 Maven | 未采用 | 按域分包已经给出边界，拆模块会让本地构建与调试变慢；真要拆服务时再用 |
| 对象存储（S3/OSS） | 未采用 | 交付物与证据是演示数据，本地文件系统足够；接对象存储需要额外的签名、分片与清理逻辑 |

# 五、硬红线

## 5.1 依赖

1. **禁止**引入 Jedis（用 Lettuce）
2. **禁止**引入 Druid（用 HikariCP）
3. **禁止**引入第二个 ORM（JPA / Hibernate / JOOQ）
4. **禁止**使用 `spring.jpa.hibernate.ddl-auto`（表结构只由 Flyway 管理）
5. **禁止**引入 Jackson 3 或任何 `tools.jackson.*` 包
6. **禁止**使用 `RestTemplate`（统一 `RestClient`）
7. **禁止**引入 Zookeeper（本项目没有注册发现）

## 5.2 代码

1. **金额禁止** `double` / `float`：统一 `BIGINT` 存"分"
2. **服务费率禁止**小数：存万分比整数（`500` = 5%），`金额 × 费率 ÷ 10000` 向下取整
3. **禁止** `System.out.println`（用 SLF4J）
4. **禁止**在 Controller 写业务逻辑（只做参数校验与转换）
5. **禁止**跨域直接调对方 Mapper（只能调对方 `application` 层 Service）
6. **禁止**域之间 join 对方的表（只存 ID）
7. **禁止**在数据库事务内做外部 HTTP 调用
8. **禁止**用"先 SELECT 判断再 UPDATE"实现幂等（必须条件更新 + 校验受影响行数）
9. **禁止**自创错误码（用 SRS 4.2 定义的）
10. **禁止**在实体上使用 `@Data` / `@EqualsAndHashCode` / `@Builder`：前两者破坏 MyBatis 一级缓存的实体身份语义，`toString` 会把敏感字段写进日志；`@Builder` 让 MyBatis 找不到无参构造

# 六、目录结构

```
com.escrow.platform
├── EscrowPlatformApplication
├── common/        响应体、异常、安全、审计、通知、聊天、缓存、脱敏
├── identity/      账号、身份、资质、会话          (D1)
├── task/          任务、名额、检索                (D2)
├── engagement/    报名、录用、合同、履约、验收     (D3)
├── fund/          账户、科目、分录、托管           (D4-1)
├── settlement/    结算、打款、对账、差异           (D4-2)
├── dispute/       争议、证据、仲裁                 (D5)
├── admin/         治理看板、用户与资质管理          (D7)
└── ai/            槽位解析、检索、重排、问答、成本台账 (D8)
```

每个域内固定四层：`api` / `application` / `domain` / `infrastructure`。

**为什么按域而不是按层**：域是稳定的，技术层会变。域内高内聚，跨域只经过 `application` 层，边界在代码结构上就是显式的。

# 七、版本变更流程

1. 先改 `pom.xml` / `package.json`，再改本文件，最后改代码
2. 跑 `mvn -B test`、`npm run typecheck`、`npm run build`
3. 校验依赖树，确认没有出现被禁止的包：

```bash
mvn -q dependency:tree -Dincludes=org.springframework.boot,com.alibaba.cloud,com.baomidou
```

4. 在本文件末尾追加变更记录，写明"改了什么 / 为什么 / 影响哪些已完成版本"

# 八、变更记录

| 版本 | 日期 | 变更 | 原因 |
|---|---|---|---|
| v1.0 | 2026-11-15 | 初版锁定 | 确定 Boot 3.5.16 + SCA 2025.0.0.0 组合 |
| v2.0 | 2026-11-22 | 重写为实际交付栈：删除未落地的分布式组件承诺，改为「评估过但未采用」并给出理由；目录结构补 `ai` 域；补前端版本表与版本变更流程 | 原稿把"计划引入"写成"必须引入"，与代码不一致 |