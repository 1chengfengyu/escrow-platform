package com.escrow.platform.settlement.domain;

/**
 * 出站支付通道（SRS A.4 T2）。
 *
 * <p>实现必须是<b>无事务</b>的远程调用：调用方在事务外调用本接口，
 * 拿到结果后再开新事务落库（T3）。V5 起模拟实现支持七种模式；
 * 真实通道仍只在 V8 之后接入。</p>
 *
 * <p><b>查证与出站分开</b>：未知态只能调 {@link #verify}，绝不能为了「确认一下」
 * 再调一次 {@link #pay}——那等于重复出款（project_rules §6）。</p>
 *
 * @since V4
 */
public interface PaymentChannel {

    /**
     * 发起一笔打款。
     *
     * @param requestNo 打款请求号（全局唯一，重试必须换新号）
     * @param amount    打款金额（分）
     * @return 通道结果与实际金额，绝不返回 null
     */
    ChannelOutcome pay(String requestNo, long amount);

    /**
     * 按请求号向通道查证（只读，不产生新的出款）。
     *
     * @param requestNo    原打款请求号
     * @param expectedAmount 本地请求金额（分），用于识别金额不一致
     * @return 查证结论与实际金额，绝不返回 null
     */
    ChannelVerificationResult verify(String requestNo, long expectedAmount);
}
