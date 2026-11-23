package com.escrow.platform.engagement.api;

import com.escrow.platform.engagement.domain.ContractStatus;

import java.time.LocalDateTime;

/**
 * 「我的签约」列表项（数据权限第一层：只含本人签约）。
 *
 * <p>只返回 {@code taskId}，不跨域取任务标题（域间只存 ID + 调接口，project_rules §3）。</p>
 *
 * @param contractId  签约 ID
 * @param taskId      任务 ID
 * @param slotId      名额 ID
 * @param status      签约状态
 * @param effectiveAt 生效时间
 * @param startedAt   开始履约时间，未开始为 {@code null}
 * @param deliveredAt 交付时间，未交付为 {@code null}
 * @since V3
 */
public record ContractListItemView(Long contractId, Long taskId, Long slotId, ContractStatus status,
                                   LocalDateTime effectiveAt, LocalDateTime startedAt,
                                   LocalDateTime deliveredAt) {
}
