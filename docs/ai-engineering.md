# AI 应用工程

AI 能力嵌在「找人」和「平台规则问答」两条业务路径里，不是独立的聊天入口。设计目标是**可评测、可降级、可追溯、可计费**，而不是接一个模型 API 就结束。

## 1. 链路

```
用户口语查询
   │
   ├─ 查询理解 QueryUnderstanding
   │    规则优先：时间、地点、预算、技能、人数 → QuerySlots
   │    模型兜底：低置信度时调用 LLM 输出槽位 JSON
   │
   ├─ 检索 Retrieval
   │    中文二元切分 + 同义词扩展 + 字段加权
   │    候选过滤：身份、资质、状态、地域
   │
   ├─ 重排 Rerank
   │    基线：确定性融合分（技能、履约、评价、价格、活跃度）
   │    可选：模型重排，受灰度比例与位移守卫约束
   │
   ├─ 生成 Generation
   │    规则问答：只允许抽取知识库片段原文
   │    推荐理由：模板化，每个字段标注来源
   │
   └─ 校验 Verification
        引用非空、引用属于检索片段、答案是被引原文子串
        不满足 → 弃答，不输出无依据内容
```

关键类：

| 阶段 | 类 |
|---|---|
| 槽位 | `SlotParser`、`QuerySlots`、`AiQueryUnderstandingService` |
| 检索 | `TextTokenizer`、`CandidateFilter`、`AiSearchService` |
| 打分 | `CandidateScorer`、`FusionWeights`、`ScoredCandidate` |
| 重排 | `RuleRerankPolicy`、`DeviationGuard`、`AiGrayRouter` |
| 生成 | `AnswerComposer`、`ReasonTemplate`、`KbRetriever` |
| 防护 | `InjectionGuard`、`AiAccessGuard` |
| 轨迹 | `AiTraceService`、`TraceStage` |

## 2. 双模式：stub 与 live

| 模式 | 触发 | 行为 |
|---|---|---|
| `stub` | `escrow.ai.mode=stub` 或无 API key | 全部走确定性实现，不联网；接口照常返回，标 `degraded=true` |
| `live` | 配置 `ESCROW_AI_API_KEY` 且 `mode=live` | 真实模型调用；失败按 `fallback-models` 回退，仍失败则降级到规则结果 |

stub 不是「假实现」，而是**可复现的基线**：槽位走规则解析，排序走确定性融合分，答案走抽取式合成。它的存在让 CI、离线评测和本地开发都不依赖外部服务。

启动期在 `live` 模式下用 3 秒超时探测接入点，不通直接启动失败，而不是留到运行期每次等超时。

## 3. 防幻觉

三道约束，缺一不可：

1. **检索约束**：答案只能基于检索到的 `KbChunk`，不把模型参数知识当事实。
2. **引用约束**：每条答案必须带引用；引用为空即弃答。
3. **子串校验**：答案必须是被引片段的原文子串（允许拼接多个片段），否则判为幻觉。

推荐理由同样受约束：理由中的每个字段必须来自本次请求实际提供的画像字段；任一条不可核，整条理由不输出。

## 4. 重排守卫

模型重排可能把明显不相关的候选人排到前面，因此设两道闸：

- **灰度**：`escrow.ai.rank.gray-ratio` 为万分比整数，按 `CRC32(userId) % 10000` 分桶；默认 0，即全量走确定性排序。
- **位移守卫**：任一候选人相对确定性排序位移超过 `deviation-guard`（默认 5），整次重排作废，回退基线顺序。

这两道闸保证「模型变差时，最坏结果等于没用模型」，而不是把排序质量交给运气。

## 5. Prompt 版本

| 常量 | 值 | 用途 |
|---|---|---|
| `PromptVersions.QUERY_SLOT` | `query-slot/v1` | 口语 → 槽位 JSON |
| `PromptVersions.RERANK` | `rerank/v1` | 候选人相关性重排 |
| `PromptVersions.RAG_ANSWER` | `rag-answer/v1` | 规则问答合成 |
| `PromptVersions.JUDGE` | `judge/v1` | 理由质量打分 |
| `PromptVersions.REASON` | `reason-template/v1` | 推荐理由模板（确定性，非模型） |

Prompt 版本进入查询缓存键：升版本天然让旧缓存失效，不需要手动清缓存。

## 6. 评测基线

数字唯一来源是 `src/test/java/com/escrow/platform/ai/AiEvalTest.java` 的输出。改基线必须重跑测试，不允许手改文档。

### 运行口径

| 项 | 值 |
|---|---|
| 模式 | `stub`（不联网、无 key） |
| 语料 | 候选人 10 人、查询 30 条、相关性标注 20 条、规则问答 20 条、理由 20 条、KB 片段 14 条 |
| 复现 | `mvn test "-Dtest=AiEvalTest"` |
| Judge 回填 | 配置 `ESCROW_AI_API_KEY`、`ESCROW_AI_MODEL=deepseek-chat` 后跑 `mvn test "-Dtest=AiLiveEvalTest"`；无 key 时整类 skip |
| 真库 + HTTP | `scripts/v10-verify.ps1`，stub 与 live 两种模式均 18/18 通过 |

### 八项指标

| # | 指标 | 目标 | 实测 | 口径 |
|---|---|---|---|---|
| 1 | 槽位 micro-F1 | ≥ 0.80 | **0.9948** | 五字段各自计 TP/FP/FN 汇总；期望与预测同时为空不计入 |
| 2 | NDCG@5 | ≥ 0.75 | **0.9898** | 相关性 0/1/2，增益 `2^rel-1`，折损 `log2(i+2)`，对 20 条查询取均值 |
| 3 | HitRate@3 | ≥ 0.85 | **1.0000** | Top-3 命中任意一条期望引用即算命中 |
| 4 | 引用准确率 | ≥ 0.90 | **0.9500** | 答案引用中属于期望引用的比例，再对答案求均值 |
| 5 | 幻觉率 | ≤ 0.05 | **0.0000** | 未弃答且「引用为空 或 答案不是被引片段原文子串」的占比 |
| 6 | 重排被拒率 | 可计算即可 | **0.50（示例 2/4）** | 位移守卫判定作废的次数占比；stub 下用构造样例验证口径 |
| 7 | 理由可核率 | = 1.00 | **1.00** | 校验范围 = 本次请求实际提供给模型的档案字段 |
| 8 | LLM-as-Judge | 可为 SKIPPED | **4.72**（3 轮：4.65 / 4.80 / 4.70） | 理由质量 1~5 均分，`deepseek-chat`，每轮 20 条 |

这些数字基于 10 人 fixture，绝对值只用于**同语料、同 prompt 版本**之间的回归对比，不代表线上效果。

## 7. 画像来源

`ProfileFieldSource` 五个值，任何字段都必须能说清来源：

| 值 | 含义 |
|---|---|
| `ACTUAL` | 用户填写 |
| `DERIVED` | 履约行为派生 |
| `USER_INPUT` | 会话中补录 |
| `MISSING` | 冷启动空值 |
| `FIXTURE` | 测试语料 |

测试数据只能标 `FIXTURE`。标成 `DERIVED` 会混淆「真实派生」与「测试语料」，让评测结果失去意义。

冷启动判据：新服务者派生字段为 NULL、来源标 `MISSING`、接口不报错。

## 8. 成本与轨迹

每次模型调用记一条 `TraceCall(tokens, micros, fallback, cacheHit)`，嵌在 `ai_trace.stages_json`：

- 估算口径：1500 micros / 1k tokens。
- 日汇总由 `PersistentAiCostLedger` 原子累加进 `ai_cost_daily`（`ON DUPLICATE KEY UPDATE`，按「日期 + 阶段」一行）。
- 进程启动时用当天已落库数量回填预算基线。
- 不建 `ai_call_log`，不跑离线汇总任务。

预算预检在每阶段调用前执行；超预算直接降级到规则结果，不让成本失控。

## 9. 已知限制

1. 重排被拒率目前只有构造样例口径，缺语料级统计；需要 live 模式跑一轮真实重排。
2. Judge 分数依赖模型与 prompt 版本，不能跨版本比较；更换模型必须重跑。
3. 检索是词法（中文二元切分 + 同义词），没有向量召回；语义近邻改写超出当前范围。
4. 语料只有 10 人，指标对小样本敏感。
5. `AiQueryCache` 是进程内缓存，多实例不共享；`ai_cost_daily` 靠数据库唯一键保证并发累加正确，但多实例同时预检预算仍各算各的。