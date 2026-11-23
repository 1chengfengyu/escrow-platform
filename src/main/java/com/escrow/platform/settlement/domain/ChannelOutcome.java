package com.escrow.platform.settlement.domain;

/**
 * 通道出站结果 + 实际金额 + 是否重复回调（V5）。
 *
 * <p>V4 的 {@code PaymentChannel#pay} 只返回 {@link ChannelResult}，无法表达
 * 「成功但金额不一致」与「回调投递两次」，V5 用本记录补齐这两个维度。</p>
 *
 * <p>{@code actualAmount} 只在 {@link ChannelResult#SUCCESS} 时有意义；
 * 未知 / 失败一律填 0，避免把「没出款」误读成「出款 0 元」。</p>
 *
 * @param result           通道原始结果
 * @param actualAmount     通道实际出款金额（分）
 * @param duplicateCallback 是否会在之后重复投递一次回调
 * @since V5
 */
public record ChannelOutcome(ChannelResult result, long actualAmount, boolean duplicateCallback) {

    public ChannelOutcome {
        if (result == null) {
            throw new IllegalArgumentException("通道结果不能为空");
        }
        if (actualAmount < 0) {
            throw new IllegalArgumentException("通道实际金额不能为负");
        }
    }

    /** 明确成功且金额一致。 */
    public static ChannelOutcome success(long actualAmount) {
        return new ChannelOutcome(ChannelResult.SUCCESS, actualAmount, false);
    }

    /** 成功但金额不一致。 */
    public static ChannelOutcome amountMismatch(long actualAmount) {
        return new ChannelOutcome(ChannelResult.SUCCESS, actualAmount, false);
    }

    /** 成功且会重复回调。 */
    public static ChannelOutcome duplicateSuccess(long actualAmount) {
        return new ChannelOutcome(ChannelResult.SUCCESS, actualAmount, true);
    }

    /** 失败 / 未知，实际金额记 0。 */
    public static ChannelOutcome of(ChannelResult result) {
        return new ChannelOutcome(result, 0L, false);
    }

    /** 与请求金额是否一致（非成功恒为 false）。 */
    public boolean matches(long requestedAmount) {
        return result == ChannelResult.SUCCESS && actualAmount == requestedAmount;
    }
}
