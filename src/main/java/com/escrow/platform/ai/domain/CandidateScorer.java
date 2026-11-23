package com.escrow.platform.ai.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 融合打分器（V10-c4）：语义 0.5 + 评分 0.3 + 完成量 0.2，全程整数。
 *
 * <p>三个分项都先折算到 0~10000 再加权：
 * <ul>
 *   <li>语义：技能精确命中记满分；否则按关键词命中率折算</li>
 *   <li>评分：{@code ratingX100 * 20}（500 分对应 10000）</li>
 *   <li>完成量：{@code min(完单数, 200) * 50}（200 单封顶）</li>
 * </ul>
 * 封顶是刻意的：完成量超过 200 单后不再加分，避免老账号永久霸榜。</p>
 *
 * @since V10
 */
public final class CandidateScorer {

    /** 完成量满分对应的单数。 */
    private static final int VOLUME_CAP = 200;

    /** 按融合分倒序；同分按 userId 升序，保证结果可复现。 */
    public List<ScoredCandidate> score(QuerySlots slots, List<CandidateProfile> candidates) {
        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (CandidateProfile profile : candidates) {
            int semantic = semanticScore(slots, profile);
            int rating = profile.ratingX100() * 20;
            int volume = Math.min(profile.completedOrders(), VOLUME_CAP) * 50;
            scored.add(new ScoredCandidate(profile, FusionWeights.fuse(semantic, rating, volume),
                    semantic, rating, volume));
        }
        scored.sort(Comparator.comparingInt(ScoredCandidate::score).reversed()
                .thenComparing(sc -> sc.profile().userId()));
        return List.copyOf(scored);
    }

    private int semanticScore(QuerySlots slots, CandidateProfile profile) {
        if (slots.skill() != null && profile.hasSkill(slots.skill())) {
            return FusionWeights.SCALE;
        }
        List<String> tokens = new ArrayList<>(slots.keywords());
        if (slots.skill() != null) {
            tokens.add(slots.skill());
        }
        if (slots.location() != null) {
            tokens.add(slots.location());
        }
        if (tokens.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (String token : tokens) {
            if (containsToken(profile, token)) {
                matched++;
            }
        }
        return matched * FusionWeights.SCALE / tokens.size();
    }

    private boolean containsToken(CandidateProfile profile, String token) {
        if (profile.skills().stream().anyMatch(skill -> skill.contains(token) || token.contains(skill))) {
            return true;
        }
        return profile.location().contains(token);
    }
}
