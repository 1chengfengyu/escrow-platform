package com.escrow.platform.dispute.api;

import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.dispute.domain.DisputeStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 争议详情视图（双方与管理员共用，V6）。
 *
 * @param id             争议单 ID
 * @param slotId         名额 ID
 * @param initiatorType  发起方
 * @param reason         争议理由
 * @param status         状态
 * @param resolution     裁决结论
 * @param resolutionBasis 裁决依据
 * @param resolverId     裁决人账号 ID
 * @param resolvedAt     裁决时间
 * @param withdrawCount  发起→撤回累计轮次
 * @param createdAt      创建时间
 * @param evidence       证据列表（对方可见）
 * @since V6
 */
public record DisputeView(Long id, Long slotId, DisputeInitiatorType initiatorType,
                          String reason, DisputeStatus status, DisputeResolution resolution,
                          String resolutionBasis, Long resolverId, LocalDateTime resolvedAt,
                          Integer withdrawCount, LocalDateTime createdAt,
                          List<DisputeEvidenceView> evidence) {
}
