package com.escrow.platform.engagement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 签约状态（SRS A.3.1 / 卡片 §2.3 已裁决 5 值）。
 *
 * <p>落库值与 {@code V8__v3_engagement_contract.sql} 的 {@code ck_engagement_contract_status}
 * 白名单逐字一致；生成列 {@code active_slot_id} 按 {@link #isActive()} 的三个取值判空。</p>
 *
 * <p>v3-c2 写 {@link #CONTRACT_ACTIVE}；v3-c3 写 {@link #CONTRACT_STARTED} / {@link #CONTRACT_CANCELLED}；
 * 交付与完成归 v3-c4。</p>
 *
 * @since V3
 */
public enum ContractStatus {

    /** 生效中：录用成功即可进入，尚未开始履约。 */
    CONTRACT_ACTIVE,

    /** 履约中：劳动者已点击开始履约（v3-c3）。 */
    CONTRACT_STARTED,

    /** 已交付：劳动者已提交交付物，等待验收（v3-c3 / v3-c4）。 */
    CONTRACT_DELIVERED,

    /** 已完成：验收通过且款项结清（终态，v3-c4）。 */
    CONTRACT_COMPLETED,

    /** 已取消：劳动者取消或未开始自动取消（终态，v3-c3）。 */
    CONTRACT_CANCELLED;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }

    /** 有效签约：与生成列唯一索引口径一致（生效中 / 履约中 / 已交付）。 */
    public boolean isActive() {
        return this == CONTRACT_ACTIVE || this == CONTRACT_STARTED || this == CONTRACT_DELIVERED;
    }

    /** 终态：已完成 / 已取消不可再迁出；名额可被再次录用（FR-SIGN.CANCEL.02）。 */
    public boolean isTerminal() {
        return this == CONTRACT_COMPLETED || this == CONTRACT_CANCELLED;
    }
}
