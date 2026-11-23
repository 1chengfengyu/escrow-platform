package com.escrow.platform.settlement.domain;

/**
 * 对账批次状态（V6，FR-RECON.JOB.01）。
 *
 * @since V6
 */
public enum ReconBatchStatus {

    /** 正在对账。 */
    RECON_RUNNING,

    /** 对账完成，差异单已落库。 */
    RECON_DONE,

    /** 对账失败（通道流水拉取异常等），可用同一日期条件重跑。 */
    RECON_FAILED
}
