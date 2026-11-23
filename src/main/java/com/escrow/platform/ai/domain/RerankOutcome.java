package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * 重排结果（V10-c7）：除了排序本身，还必须交代「有没有真的重排」「有没有被守卫丢弃」
 * 「最大位移是多少」——这三项是重排被拒率指标与 trace 回放的原始数据。
 *
 * @param ranked         最终顺序（被拒时等于确定性顺序）
 * @param applied        是否执行了重排
 * @param rejected       是否因位移超限被丢弃
 * @param maxDisplacement 与确定性顺序相比的最大位置变化
 * @param note           人类可读说明，写日志与 trace
 * @since V10
 */
public record RerankOutcome(List<ScoredCandidate> ranked, boolean applied, boolean rejected,
                            int maxDisplacement, String note) {

    public RerankOutcome {
        ranked = List.copyOf(ranked);
    }

    /** 未启用重排时的空结果。 */
    public static RerankOutcome skipped(List<ScoredCandidate> base) {
        return new RerankOutcome(base, false, false, 0, "WEIGHTED 基线，不启用重排");
    }
}
