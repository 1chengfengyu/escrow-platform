package com.escrow.platform.settlement.api;

import com.escrow.platform.settlement.domain.ChannelResult;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 管理端打款单视图（V5 处置台）。
 *
 * <p>只暴露处置需要的字段；{@code successSetttlementId} 生成列、内部时间戳不外泄。</p>
 *
 * @param id            打款单 ID
 * @param settlementId  结算单 ID
 * @param requestNo     请求号
 * @param attemptSeq    尝试序号
 * @param amount        请求金额（分）
 * @param actualAmount  实际金额（分，未确认时为空）
 * @param status        当前状态
 * @param channelResult 通道原始结果
 * @param statusEnteredAt 进入当前状态时间
 * @param ageMinutes    账龄（分钟）
 * @since V5
 */
public record PayoutAdminView(Long id, Long settlementId, String requestNo, Integer attemptSeq,
                              long amount, Long actualAmount, PayoutStatus status,
                              ChannelResult channelResult, LocalDateTime statusEnteredAt,
                              long ageMinutes) {

    /**
     * 由实体组装视图。
     *
     * @param order 打款单
     * @return 视图
     */
    public static PayoutAdminView of(PayoutOrder order) {
        long age = order.getStatusEnteredAt() == null ? 0L
                : Math.max(0L, Duration.between(order.getStatusEnteredAt(), LocalDateTime.now()).toMinutes());
        return new PayoutAdminView(order.getId(), order.getSettlementId(), order.getRequestNo(),
                order.getAttemptSeq(), order.getAmount(), order.getActualAmount(), order.getStatus(),
                order.getChannelResult(), order.getStatusEnteredAt(), age);
    }
}
