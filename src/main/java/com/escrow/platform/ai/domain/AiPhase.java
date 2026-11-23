package com.escrow.platform.ai.domain;

/**
 * AI 链路的五个阶段，用于 token 预算、成本归因与 trace 分阶段记录（V10-c1/c6）。
 *
 * <p>只有 {@link #UNDERSTAND}、{@link #RERANK}、{@link #GENERATE} 会消耗模型 token；
 * {@link #FILTER}、{@link #RECALL} 是确定性计算，token 恒为 0，但仍然记录耗时，
 * 这样 trace 回放时能看出时间花在 SQL 还是模型上。</p>
 *
 * @since V10
 */
public enum AiPhase {

    /** 口语 → 结构化槽位（模型或规则）。 */
    UNDERSTAND,

    /** 价格/地域/时间/评分等条件过滤（纯 SQL）。 */
    FILTER,

    /** 词法召回 + 融合打分（纯 Java）。 */
    RECALL,

    /** 语义重排（模型，可被位移守卫丢弃）。 */
    RERANK,

    /** RAG 答案或推荐理由生成（模板/抽取式）。 */
    GENERATE
}
