package com.escrow.platform.ai.domain;

/**
 * 排序策略（V10-c7）：灰度只在这两个值之间切换，不涉及权重调整。
 *
 * @since V10
 */
public enum AiRankStrategy {

    /** 只用确定性融合分（语义 0.5 + 评分 0.3 + 完成量 0.2）。 */
    WEIGHTED,

    /** 在融合分之后追加语义重排（可能被位移守卫丢弃）。 */
    RERANK;

    /** 配置解析：只接受这两个字面量，大小写不敏感，其他值直接失败而不是静默降级。 */
    public static AiRankStrategy parse(String wire) {
        if (wire == null || wire.isBlank()) {
            return WEIGHTED;
        }
        try {
            return valueOf(wire.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("escrow.ai.rank.strategy 只允许 WEIGHTED 或 RERANK");
        }
    }
}
