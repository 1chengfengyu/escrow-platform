package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * 服务者画像（V10-c3）：检索、融合打分与推荐理由的<b>唯一事实来源</b>。
 *
 * <p>模型不参与写入，也不参与修改。{@code skills} 是用户填的自由文本，
 * 落库前必须经 {@link InjectionGuard#sanitizeTags} 清洗——这是间接提示注入的第一道防线。</p>
 *
 * <p>评分用 {@code ratingX100}（0~500）存整数，禁止浮点；金额 {@code expectedUnitPrice}
 * 单位是分，与全项目口径一致。</p>
 *
 * @param userId           服务者用户ID
 * @param skills           技能标签，已清洗、已去重
 * @param experienceYears  从业年数
 * @param expectedUnitPrice 期望单价（分）
 * @param location         可到岗城市
 * @param weekendAvailable 周末是否可接单
 * @param completedOrders  完单数
 * @param ratingX100       评分 ×100（0~500）
 * @since V10
 */
public record CandidateProfile(Long userId, List<String> skills, int experienceYears,
                               long expectedUnitPrice, String location, boolean weekendAvailable,
                               int completedOrders, int ratingX100) {

    public CandidateProfile {
        skills = skills == null ? List.of() : List.copyOf(skills);
        if (expectedUnitPrice <= 0) {
            throw new IllegalArgumentException("expectedUnitPrice 必须大于 0");
        }
        if (ratingX100 < 0 || ratingX100 > 500) {
            throw new IllegalArgumentException("ratingX100 必须在 0~500");
        }
    }

    /** 技能是否精确命中（用于融合分的语义项与理由模板的证据）。 */
    public boolean hasSkill(String skill) {
        return skill != null && skills.contains(skill);
    }
}
