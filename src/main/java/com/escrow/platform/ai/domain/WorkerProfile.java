package com.escrow.platform.ai.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务者画像的完整视图（V10-c3）：允许字段缺失，用于冷启动。
 *
 * <p>{@link CandidateProfile} 是"可参与检索的完整画像"，本类是其超集：
 * 冷启动的服务者没有报价/城市/评分，仍然可以入库、可以被查询，只是不进入候选集。
 * 接口返回时带 {@code sources}，前端据此展示"未填写"而不是 0 元 / 0 分。</p>
 *
 * @param userId   用户ID
 * @param skills   技能，允许空列表
 * @param experienceYears 从业年数，未知为 null
 * @param expectedUnitPrice 期望单价（分），未知为 null
 * @param location 城市，未知为 null
 * @param weekendAvailable 周末可接单，未知为 null
 * @param completedOrders 完单数，未知为 null
 * @param ratingX100 评分×100，未知为 null
 * @param sources  字段来源映射，键为字段名
 * @since V10
 */
public record WorkerProfile(Long userId, List<String> skills, Integer experienceYears,
                            Long expectedUnitPrice, String location, Boolean weekendAvailable,
                            Integer completedOrders, Integer ratingX100,
                            Map<String, ProfileFieldSource> sources) {

    public WorkerProfile {
        skills = skills == null ? List.of() : List.copyOf(skills);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    /**
     * 转成可检索画像；缺必要字段（报价）时返回空。
     *
     * <p>只要求报价非空，其余字段缺失按默认值处理：评分不足按 0 分计，
     * 完单数为 0，城市为空则不参与地域筛选。这样冷启动用户不会报错，
     * 只是排序靠后——符合"新人不该被系统拒绝"的产品要求。</p>
     */
    public Optional<CandidateProfile> toCandidate() {
        if (expectedUnitPrice == null || expectedUnitPrice <= 0) {
            return Optional.empty();
        }
        return Optional.of(new CandidateProfile(userId, skills,
                experienceYears == null ? 0 : experienceYears,
                expectedUnitPrice,
                location == null ? "" : location,
                Boolean.TRUE.equals(weekendAvailable),
                completedOrders == null ? 0 : completedOrders,
                ratingX100 == null ? 0 : ratingX100));
    }

    /** 某字段的来源；没记录按 MISSING 处理，绝不默认成 ACTUAL。 */
    public ProfileFieldSource sourceOf(String field) {
        return sources.getOrDefault(field, ProfileFieldSource.MISSING);
    }
}
