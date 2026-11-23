package com.escrow.platform.settlement.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结算费率测试（FR-SETTLE.FEE.01~.03，v4-c2）。
 *
 * <p>费率是万分数整数，服务费向下取整；实付 + 服务费恒等于应结。</p>
 *
 * @since V4
 */
class SettlementFeeCalculatorTest {

    /** FR-SETTLE.FEE.01：100 元 × 3% = 3 元，实付 97 元。 */
    @Test
    void shouldChargeThreePercent() {
        assertThat(SettlementFeeCalculator.feeOf(10_000L, 300)).isEqualTo(300L);
        assertThat(SettlementFeeCalculator.netOf(10_000L, 300)).isEqualTo(9_700L);
    }

    /** FR-SETTLE.FEE.02：服务费向下取整，不四舍五入。 */
    @Test
    void shouldFloorFee() {
        assertThat(SettlementFeeCalculator.feeOf(9_999L, 300)).isEqualTo(299L);
        assertThat(SettlementFeeCalculator.netOf(9_999L, 300)).isEqualTo(9_700L);
    }

    /** FR-SETTLE.FEE.02：奇数分也不能让费与实付之和偏离应结。 */
    @Test
    void feePlusNetShouldAlwaysEqualPayable() {
        for (long payable = 1; payable <= 2_000; payable++) {
            for (int rate : new int[]{0, 1, 299, 300, 333, 5_000, 9_999, 10_000}) {
                long fee = SettlementFeeCalculator.feeOf(payable, rate);
                assertThat(fee + SettlementFeeCalculator.netOf(payable, rate))
                        .as("payable=%s rate=%s", payable, rate)
                        .isEqualTo(payable);
            }
        }
    }

    /** FR-SETTLE.FEE.03：100% 费率即零实付。 */
    @Test
    void fullRateShouldProduceZeroNet() {
        assertThat(SettlementFeeCalculator.netOf(12_345L, 10_000)).isZero();
        assertThat(SettlementFeeCalculator.feeOf(12_345L, 10_000)).isEqualTo(12_345L);
    }

    @Test
    void invalidArgumentsShouldThrow() {
        assertThatThrownBy(() -> SettlementFeeCalculator.feeOf(0L, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementFeeCalculator.feeOf(-1L, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementFeeCalculator.feeOf(100L, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementFeeCalculator.feeOf(100L, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
