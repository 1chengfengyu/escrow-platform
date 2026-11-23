package com.escrow.platform.task.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 名额状态（SRS A.3.1「名额状态机」，docs/decisions.md #47）。
 *
 * <p>落库值为枚举名（docs/decisions.md #20【V2 修订】），与 {@code ck_task_slot_status} 的
 * {@code IN} 白名单逐字一致——由 {@code V2TaskSchemaContractTest} 契约测试防「插入即被拒」。</p>
 *
 * <p>状态迁移（{@code transit}）归 v2-c5 / v2-c3：本卡只落枚举与终态判定，不写状态机。</p>
 *
 * @since V2
 */
public enum SlotStatus {

    /** 待占用：名额可被录用；撤单 / 到期时退款并转入已释放。 */
    SLOT_AVAILABLE,

    /** 已占用：已录用未开始履约。 */
    SLOT_OCCUPIED,

    /** 履约中：签约生效后进入履约。 */
    SLOT_IN_PROGRESS,

    /** 已交付：劳动者已提交交付物，待雇主验收。 */
    SLOT_DELIVERED,

    /** 待裁决：存在争议单或打款人工失败，等待资金处置。 */
    SLOT_PENDING_ARBITRATION,

    /** 待结算：验收通过，等待打款。 */
    SLOT_PENDING_SETTLEMENT,

    /** 已完结：打款成功，钱到劳动者（终态）。 */
    SLOT_COMPLETED,

    /** 已退款：钱退回雇主（终态）。 */
    SLOT_REFUNDED,

    /** 已释放：撤单 / 到期把待占用名额退款后关闭，永不补员（终态）。 */
    SLOT_RELEASED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }

    /**
     * 是否终态：已完结 / 已退款 / 已释放三者不可再迁出
     * （唯一例外是差异①回退重试，由 v2-c5 在资金域处理，不动名额状态）。
     */
    public boolean isTerminal() {
        return this == SLOT_COMPLETED || this == SLOT_REFUNDED || this == SLOT_RELEASED;
    }
}
