package com.escrow.platform.settlement.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日终对账批次（V6，FR-RECON.JOB.01），对应表 {@code recon_batch}（V14）。
 *
 * <p>按日期唯一：同一天只允许一个批次，重跑通过「按状态条件更新」把
 * {@code RECON_DONE / RECON_FAILED} 重置为 {@code RECON_RUNNING}，
 * 不做「先查再插」，并发下也只会有一个批次。</p>
 *
 * @since V6
 */
@Getter
@Setter
@TableName("recon_batch")
public class ReconBatch {

    @TableId
    private Long id;

    /** 对账日期（自然日）。 */
    private LocalDate reconDate;

    /** 批次状态，见 {@link ReconBatchStatus}。 */
    private ReconBatchStatus status;

    /** 本地打款尝试笔数。 */
    private Integer localCount;

    /** 通道流水笔数。 */
    private Integer channelCount;

    /** 差异单笔数。 */
    private Integer diffCount;

    /** 差异金额绝对值合计（分）。 */
    private Long diffAmount;

    /** 六科目恒等式是否平衡：1 平衡 / 0 不平衡。 */
    private Integer identityOk;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
