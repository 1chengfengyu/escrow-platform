package com.escrow.platform.settlement.domain;

/**
 * 查证结果：结论 + 通道实际金额（V5）。
 *
 * <p>金额不一致时人工需要看到通道一审的实际金额，因此查证不能只返回枚举。
 * 查不到（{@code NOT_FOUND} / {@code ERROR} / {@code FAIL}）时 {@code actualAmount} 为 0，
 * 由消费方按 {@code result} 判断是否有意义。</p>
 *
 * @param result       查证结论
 * @param actualAmount 通道实际金额（分）
 * @since V5
 */
public record ChannelVerificationResult(ChannelVerification result, long actualAmount) {

    public ChannelVerificationResult {
        if (result == null) {
            throw new IllegalArgumentException("查证结论不能为空");
        }
    }

    /** 查到且成功。 */
    public static ChannelVerificationResult success(long actualAmount) {
        return new ChannelVerificationResult(ChannelVerification.SUCCESS, actualAmount);
    }

    /** 查到但金额不一致。 */
    public static ChannelVerificationResult amountMismatch(long actualAmount) {
        return new ChannelVerificationResult(ChannelVerification.AMOUNT_MISMATCH, actualAmount);
    }

    /** 查到且通道明确失败。 */
    public static ChannelVerificationResult fail() {
        return new ChannelVerificationResult(ChannelVerification.FAIL, 0L);
    }

    /** 通道侧无记录。 */
    public static ChannelVerificationResult notFound() {
        return new ChannelVerificationResult(ChannelVerification.NOT_FOUND, 0L);
    }

    /** 查证调用本身失败。 */
    public static ChannelVerificationResult error() {
        return new ChannelVerificationResult(ChannelVerification.ERROR, 0L);
    }
}
