package com.escrow.platform.ai.domain;

/**
 * 画像字段来源（V10-c3）：区分"真实事实"与"测试语料"，禁止混用。
 *
 * <p>{@link #FIXTURE} 是专门为评测语料加的第五个值。测试数据必须标 FIXTURE，
 * <b>不得标 DERIVED</b>——否则面试演示时无法说明哪些数字来自真实链路，
 * 也会让"可核率"变成自证。</p>
 *
 * @since V10
 */
public enum ProfileFieldSource {

    /** 用户直接填报。 */
    ACTUAL,

    /** 由真实业务数据派生（如完单数从结算单聚合）。 */
    DERIVED,

    /** 本次请求由用户临时输入，未落库。 */
    USER_INPUT,

    /** 该字段缺失，业务必须容忍。 */
    MISSING,

    /** 测试语料，只允许出现在 src/test/resources/ai/。 */
    FIXTURE
}
