package com.escrow.platform.settlement.domain;

/**
 * 结算金额计算（FR-SETTLE.FEE.01~.03）。
 *
 * <p>费率是万分数整数：{@code feeRate=300} 表示 3%，{@code 10000} 表示 100%。
 * 服务费一律向下取整：{@code fee = floor(payable × feeRate / 10000)}，
 * 实付 {@code net = payable − fee}，保证库层 {@code ck_settlement_sum} 恒成立。</p>
 *
 * <p>纯 Java，不依赖 Spring / MyBatis，便于单测穷举边界。</p>
 *
 * @since V4
 */
public final class SettlementFeeCalculator {

    /** 费率上限（万分数），与库层 {@code ck_settlement_fee_rate} 一致。 */
    public static final int MAX_FEE_RATE = 10_000;

    private SettlementFeeCalculator() {
    }

    /**
     * 服务费 = floor(应结 × 费率 ÷ 10000)。
     *
     * @param payableAmount 应结金额（分），必须 &gt; 0
     * @param feeRate       费率（万分数），0~10000
     * @return 服务费（分）
     * @throws IllegalArgumentException 金额或费率越界
     */
    public static long feeOf(long payableAmount, int feeRate) {
        if (payableAmount <= 0) {
            throw new IllegalArgumentException("应结金额必须大于 0");
        }
        if (feeRate < 0 || feeRate > MAX_FEE_RATE) {
            throw new IllegalArgumentException("费率必须在 0~10000 之间");
        }
        return Math.multiplyExact(payableAmount, (long) feeRate) / MAX_FEE_RATE;
    }

    /**
     * 实付 = 应结 − 服务费。
     *
     * @param payableAmount 应结金额（分）
     * @param feeRate       费率（万分数）
     * @return 实付金额（分），可能为 0（零实付）
     */
    public static long netOf(long payableAmount, int feeRate) {
        return payableAmount - feeOf(payableAmount, feeRate);
    }
}
