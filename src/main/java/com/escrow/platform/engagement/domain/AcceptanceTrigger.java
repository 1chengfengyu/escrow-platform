package com.escrow.platform.engagement.domain;

/**
 * 验收触发方（审计字段 {@code trigger_source}，FR-ACCEPT.AUTO.01）。
 *
 * @since V4
 */
public enum AcceptanceTrigger {

    /** 雇主手工验收。 */
    USER,

    /** 交付满 48 小时系统自动验收。 */
    SYSTEM
}
