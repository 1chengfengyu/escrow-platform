package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * RAG 答案（V10-c5）：抽取式合成，不允许自由生成。
 *
 * <p>{@code abstain=true} 表示知识库没有足够依据，回答"未收录"。弃答不计入幻觉率——
 * 幻觉的定义是"编造了不存在的事实"，弃答恰恰是它的反面。</p>
 *
 * @param answer    答案文本；弃答时是固定提示语
 * @param citations 引用ID（只能是检索返回过的）
 * @param abstain   是否弃答
 * @since V10
 */
public record AnswerDraft(String answer, List<String> citations, boolean abstain) {

    public AnswerDraft {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 有引用且未弃答。 */
    public boolean cited() {
        return !abstain && !citations.isEmpty();
    }
}
