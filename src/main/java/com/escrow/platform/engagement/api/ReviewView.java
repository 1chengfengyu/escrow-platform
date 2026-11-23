package com.escrow.platform.engagement.api;

import java.time.LocalDateTime;

/**
 * 评价视图（V7，FR-REPUT.RATE.01 / FR-REPUT.RATE.02）。
 *
 * @param reviewerId       评价人账号 ID
 * @param reviewerNickname 评价人昵称
 * @param rating           评分 1~5
 * @param comment          文字评价，可空
 * @param createdAt        评价时间
 * @since V7
 */
public record ReviewView(
        Long reviewerId,
        String reviewerNickname,
        int rating,
        String comment,
        LocalDateTime createdAt) {
}
