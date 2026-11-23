package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * 推荐理由（V10-c4/c7）：由模板拼真实字段，**不含任何模型生成的事实**。
 *
 * <p>可核率 = 1.00 的实现方式：每一段都必须带 {@code evidenceField}（画像字段名）
 * 与 {@code evidenceValue}（该字段的原始值），校验时只要在对应字段里找不到这个值，
 * 整条理由即判为不可核。</p>
 *
 * @param text     完整理由（各段用「；」连接）
 * @param segments 分段，每段可单独回溯
 * @since V10
 */
public record ReasonDraft(String text, List<ReasonSegment> segments) {

    public ReasonDraft {
        segments = List.copyOf(segments);
    }

    /** 单段理由及其证据。 */
    public record ReasonSegment(String text, String evidenceField, String evidenceValue) {
    }

    /** 每段都能在画像字段里找到证据值。 */
    public boolean verifiable() {
        return segments.stream().allMatch(segment ->
                segment.evidenceField() != null
                        && segment.evidenceValue() != null
                        && !segment.evidenceValue().isBlank());
    }
}
