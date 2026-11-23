package com.escrow.platform.ai.domain;

/**
 * Prompt 版本号常量（V10-c7 验收要求：基线文档必须注明 prompt 版本）。
 *
 * <p>版本号一旦随语义变更（改评分维度、改输出字段、改判定口径）就必须递增，
 * 否则两批评测数字不可比较。缓存键也包含版本号，升版本天然让旧缓存失效。</p>
 *
 * @since V10
 */
public final class PromptVersions {

    /** 口语 → 槽位 JSON。 */
    public static final String QUERY_SLOT = "query-slot/v1";

    /** 候选人与岗位需求的语义相关性重排。 */
    public static final String RERANK = "rerank/v1";

    /** RAG 答案合成（只允许引用检索片段原文）。 */
    public static final String RAG_ANSWER = "rag-answer/v1";

    /** LLM-as-Judge 理由质量打分。 */
    public static final String JUDGE = "judge/v1";

    /** 推荐理由模板（非模型，版本用于文档追溯）。 */
    public static final String REASON = "reason-template/v1";

    private PromptVersions() {
    }
}
