package com.escrow.platform.engagement.api;

import com.escrow.platform.common.web.PageView;

/**
 * 劳动者信誉视图（V7，FR-REPUT.RATE.01 的派生视图）。
 *
 * <p>比率一律用整数万分比，不用小数：{@code timeoutRateBps = 250} 表示 2.50%；
 * {@code averageRatingX100 = 460} 表示平均 4.60 分。这样接口契约里没有浮点数，
 * 前端也不会因为二进制小数出现 4.599999 的展示。</p>
 *
 * @param userId              劳动者账号 ID
 * @param completedContracts  已完成签约数
 * @param timeoutCancels      超时取消次数
 * @param timeoutRateBps      超时率（万分比）
 * @param averageRatingX100   平均评分 ×100；无评价时为 0
 * @param reviewCount         收到的评价数
 * @param reviews             评价分页
 * @since V7
 */
public record ReputationView(
        Long userId,
        long completedContracts,
        long timeoutCancels,
        int timeoutRateBps,
        int averageRatingX100,
        long reviewCount,
        PageView<ReviewView> reviews) {
}
