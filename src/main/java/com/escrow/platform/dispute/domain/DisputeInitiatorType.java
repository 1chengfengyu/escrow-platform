package com.escrow.platform.dispute.domain;

/**
 * 争议发起方（表 {@code dispute.initiator_type}）。
 *
 * @since V4
 */
public enum DisputeInitiatorType {

    /** 雇主发起（V4 拒收场景）。 */
    EMPLOYER,

    /** 劳动者发起（V5）。 */
    WORKER
}
