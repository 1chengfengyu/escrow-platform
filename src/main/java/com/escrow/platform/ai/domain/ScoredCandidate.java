package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * 融合打分后的候选人（V10-c4）。
 *
 * <p>{@code score} 与三个分项都是 0~10000 的整数，便于评测与线上比较；
 * 展示给用户时再除以 100 换成百分制。{@code breakdown} 用于 trace 回放与客服排查。</p>
 *
 * @param profile     画像
 * @param score       融合分 0~10000
 * @param semantic    语义分项 0~10000
 * @param rating      评分分项 0~10000
 * @param volume      完成量分项 0~10000
 * @since V10
 */
public record ScoredCandidate(CandidateProfile profile, int score, int semantic, int rating, int volume) {

    /** 结构化分项，写进 trace 的 {@code stages_json}。 */
    public List<Integer> breakdown() {
        return List.of(semantic, rating, volume);
    }

    /** 替换分数（重排后保留原分项，只改总分）。 */
    public ScoredCandidate withScore(int newScore) {
        return new ScoredCandidate(profile, newScore, semantic, rating, volume);
    }
}
