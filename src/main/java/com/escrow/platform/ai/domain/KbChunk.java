package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * 知识库片段（V10-c5）：RAG 的检索单元。
 *
 * <p>{@code citationId} 是引用唯一标识，答案里只能出现检索返回过的 citationId，
 * 且答案文本必须是片段的原文子串——两条合起来让"编造引用"在结构上不可能通过校验。</p>
 *
 * @param citationId 引用ID，如 {@code KB-PAYOUT-01}
 * @param title      条目标题
 * @param text       条目正文（答案只能摘取其中的句子）
 * @param source     来源（SRS 条款号或文档路径），便于人工复核
 * @since V10
 */
public record KbChunk(String citationId, String title, String text, String source) {

    public KbChunk {
        if (citationId == null || citationId.isBlank()) {
            throw new IllegalArgumentException("citationId 不能为空");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
    }
}
