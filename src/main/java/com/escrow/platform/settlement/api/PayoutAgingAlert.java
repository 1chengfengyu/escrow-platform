package com.escrow.platform.settlement.api;

import com.escrow.platform.settlement.domain.PayoutStatus;

import java.time.LocalDateTime;

/**
 * 账龄告警条目（V5，FR-JOB.AGING.01）。
 *
 * @param payoutId        打款单 ID
 * @param settlementId    结算单 ID
 * @param requestNo       请求号
 * @param status          当前状态
 * @param amount          请求金额（分）
 * @param statusEnteredAt 进入当前状态时间
 * @param ageMinutes      账龄（分钟）
 * @since V5
 */
public record PayoutAgingAlert(Long payoutId, Long settlementId, String requestNo,
                               PayoutStatus status, long amount,
                               LocalDateTime statusEnteredAt, long ageMinutes) {
}
