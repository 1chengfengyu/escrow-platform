package com.escrow.platform.ai.domain;

/**
 * 候选人硬过滤条件（V10-c4 的 FILTER 阶段）。
 *
 * <p>用户要求的第一原则：**能用确定性条件判断的，不交给模型**。价格、地域、
 * 周末、评分、完单数全部在这里比较；模型只在过滤后的候选集上做语义重排。</p>
 *
 * <p>在线实现对应 SQL 的 {@code WHERE}（等值 / 范围），离线评测对同一组条件
 * 在内存语料上执行，两边语义必须一致。</p>
 *
 * @param priceMax  期望单价上限（分），{@code null} 表示不筛
 * @param location  城市，{@code null} 表示不筛
 * @param weekend   是否只要周末可接单
 * @param minRatingX100 最低评分 ×100
 * @param minOrders 最低完单数
 * @since V10
 */
public record CandidateFilter(Long priceMax, String location, boolean weekend,
                              int minRatingX100, int minOrders) {

    /** 由槽位生成过滤条件；槽位没给的条件一律不筛，绝不擅自收紧。 */
    public static CandidateFilter from(QuerySlots slots) {
        return new CandidateFilter(
                slots.priceMax(),
                slots.location(),
                Boolean.TRUE.equals(slots.weekend()),
                0,
                0);
    }

    /** 单条画像是否通过过滤。 */
    public boolean matches(CandidateProfile profile) {
        if (priceMax != null && profile.expectedUnitPrice() > priceMax) {
            return false;
        }
        if (location != null && !location.equals(profile.location())) {
            return false;
        }
        if (weekend && !profile.weekendAvailable()) {
            return false;
        }
        return profile.ratingX100() >= minRatingX100 && profile.completedOrders() >= minOrders;
    }
}
