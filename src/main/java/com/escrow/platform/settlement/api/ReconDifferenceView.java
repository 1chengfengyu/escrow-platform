package com.escrow.platform.settlement.api;

import com.escrow.platform.settlement.domain.DifferenceResolution;
import com.escrow.platform.settlement.domain.DifferenceStatus;
import com.escrow.platform.settlement.domain.DifferenceType;
import com.escrow.platform.settlement.domain.SettlementDifference;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 管理端对账差异视图（V6）。
 *
 * @param id              差异单 ID
 * @param reconDate       对账日
 * @param diffType        差异类型
 * @param localRecordNo   本地记录号（打款请求号）
 * @param channelRecordNo 通道记录号
 * @param localAmount     本地金额（分）
 * @param channelAmount   通道金额（分）
 * @param status          差异单状态
 * @param resolution      处置结果
 * @param resolverId      处置人账号 ID
 * @param resolvedAt      处置时间
 * @since V6
 */
public record ReconDifferenceView(Long id, LocalDate reconDate, DifferenceType diffType,
                                  String localRecordNo, String channelRecordNo,
                                  Long localAmount, Long channelAmount, DifferenceStatus status,
                                  DifferenceResolution resolution, Long resolverId,
                                  LocalDateTime resolvedAt) {

    /**
     * 由实体组装视图。
     *
     * @param difference 差异单
     * @return 视图
     */
    public static ReconDifferenceView of(SettlementDifference difference) {
        return new ReconDifferenceView(difference.getId(), difference.getReconDate(),
                difference.getDiffType(), difference.getLocalRecordNo(),
                difference.getChannelRecordNo(), difference.getLocalAmount(),
                difference.getChannelAmount(), difference.getStatus(),
                difference.getResolution(), difference.getResolverId(), difference.getResolvedAt());
    }
}
