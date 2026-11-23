package com.escrow.platform.dispute.api;

import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeStatus;

import java.time.LocalDateTime;

/**
 * 争议列表行（管理端待裁决 / 超期列表，V6）。
 *
 * @param id            争议单 ID
 * @param slotId        名额 ID
 * @param initiatorType 发起方
 * @param reason        争议理由
 * @param status        状态
 * @param withdrawCount 已撤回次数
 * @param createdAt     创建时间
 * @param pendingDays   已等待天数（仅超期列表有意义）
 * @since V6
 */
public record DisputeSummaryView(Long id, Long slotId, DisputeInitiatorType initiatorType,
                                 String reason, DisputeStatus status, Integer withdrawCount,
                                 LocalDateTime createdAt, Long pendingDays) {
}
