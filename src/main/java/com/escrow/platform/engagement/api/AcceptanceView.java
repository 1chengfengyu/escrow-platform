package com.escrow.platform.engagement.api;

import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.task.domain.SlotStatus;

/**
 * 验收结果视图。
 *
 * @param contractId     签约 ID
 * @param contractStatus 验收后的签约状态
 * @param slotStatus     验收后的名额状态
 * @param disputeId      拒收产生的争议单 ID，可空
 * @param settlementId   结算单 ID，可空（拒收时无）
 * @param payoutId       打款单 ID，可空（零实付 / 拒收时无）
 * @since V4
 */
public record AcceptanceView(Long contractId, ContractStatus contractStatus, SlotStatus slotStatus,
                             Long disputeId, Long settlementId, Long payoutId) {
}
