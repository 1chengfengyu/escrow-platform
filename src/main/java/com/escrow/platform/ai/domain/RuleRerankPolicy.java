package com.escrow.platform.ai.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 确定性重排策略（V10-c4）：stub 模式与「模型重排被守卫丢弃」时的兜底。
 *
 * <p>加分项全部来自用户明确表达的需求，且每一项都能在画像里找到证据：
 * 技能命中、周末档期、报价在预算内、经验年数。没有任何一项由模型判断。</p>
 *
 * <p>意义不只是兜底：它同时是模型的<b>对照组</b>——位移守卫拿它当基准，
 * 一旦模型排序与它偏离过大就作废模型结果（假设模型会被注入欺骗）。</p>
 *
 * @since V10
 */
public final class RuleRerankPolicy {

    private static final int SKILL_BONUS = 3000;
    private static final int WEEKEND_BONUS = 1000;
    private static final int PRICE_BONUS = 1000;
    private static final int EXPERIENCE_CAP = 10;
    private static final int EXPERIENCE_UNIT = 50;

    /** 在融合分基础上加确定性证据分，再排序。 */
    public List<ScoredCandidate> rerank(QuerySlots slots, List<ScoredCandidate> base) {
        List<ScoredCandidate> reranked = new ArrayList<>(base.size());
        for (ScoredCandidate candidate : base) {
            CandidateProfile profile = candidate.profile();
            int bonus = 0;
            if (slots.skill() != null && profile.hasSkill(slots.skill())) {
                bonus += SKILL_BONUS;
            }
            if (Boolean.TRUE.equals(slots.weekend()) && profile.weekendAvailable()) {
                bonus += WEEKEND_BONUS;
            }
            if (slots.priceMax() != null && profile.expectedUnitPrice() <= slots.priceMax()) {
                bonus += PRICE_BONUS;
            }
            bonus += Math.min(profile.experienceYears(), EXPERIENCE_CAP) * EXPERIENCE_UNIT;
            reranked.add(candidate.withScore(candidate.score() + bonus));
        }
        reranked.sort(Comparator.comparingInt(ScoredCandidate::score).reversed()
                .thenComparing(sc -> sc.profile().userId()));
        return List.copyOf(reranked);
    }
}
