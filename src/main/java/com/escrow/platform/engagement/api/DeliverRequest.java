package com.escrow.platform.engagement.api;

import java.util.List;

/**
 * 提交交付物请求体（FR-DELIVER.SUBMIT.01）。
 *
 * <p>Controller 只负责把 {@code images / description} 交给
 * {@code DeliverableSubmission.from} 做校验与归一，不在 DTO 上堆注解。</p>
 *
 * @param images      图片 URL 列表，1~9 个
 * @param description 文字说明，≤500 字，可为空
 * @since V3
 */
public record DeliverRequest(List<String> images, String description) {
}
