package com.escrow.platform.engagement.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 履约事件类型（SRS 4.2.2「履约记录」：开始 / 交付 / 取消 / 超时取消）。
 *
 * <p>落库值与 {@code V9__v3_performance_record.sql} 的
 * {@code ck_engagement_performance_record_event} 白名单逐字一致。
 * 本卡只写开始 / 取消 / 超时取消；交付归 v3-c4。</p>
 *
 * @since V3
 */
public enum PerformanceEventType {

    /** 劳动者开始履约（FR-SIGN.START.01）。 */
    PERFORMANCE_START,

    /** 劳动者提交交付物（FR-DELIVER.SUBMIT.02，v3-c4）。 */
    PERFORMANCE_DELIVER,

    /** 劳动者主动取消签约（FR-SIGN.CANCEL.01）。 */
    PERFORMANCE_CANCEL,

    /** 签约生效后超时未开始，系统自动取消（FR-SIGN.START.02）。 */
    PERFORMANCE_TIMEOUT_CANCEL;

    /** 落库值，取 {@code name()}。 */
    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
