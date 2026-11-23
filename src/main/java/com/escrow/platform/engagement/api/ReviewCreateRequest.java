package com.escrow.platform.engagement.api;

/**
 * 创建签约评价的请求体（V7，FR-REPUT.RATE.01 / FR-REPUT.RATE.02）。
 *
 * @param rating  评分，1~5，必填
 * @param comment 文字评价，至多 200 字，可空
 * @since V7
 */
public record ReviewCreateRequest(Integer rating, String comment) {
}
