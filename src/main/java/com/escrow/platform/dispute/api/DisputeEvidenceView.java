package com.escrow.platform.dispute.api;

import com.escrow.platform.dispute.domain.DisputeInitiatorType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 争议证据视图（V6）。
 *
 * @param id            证据 ID
 * @param disputeId     争议单 ID
 * @param submitterId   提交人账号 ID
 * @param submitterType 提交方
 * @param content       文字证据
 * @param imageUrls     图片 URL 列表
 * @param createdAt     提交时间
 * @since V6
 */
public record DisputeEvidenceView(Long id, Long disputeId, Long submitterId,
                                  DisputeInitiatorType submitterType, String content,
                                  List<String> imageUrls, LocalDateTime createdAt) {
}
