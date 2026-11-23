package com.escrow.platform.settlement.application;

import java.time.LocalDate;

/**
 * 一次对账执行结果（V6，FR-RECON.JOB.01）。
 *
 * @param batchId      批次 ID
 * @param reconDate    对账日期
 * @param localCount   本地打款尝试笔数
 * @param channelCount 通道流水笔数
 * @param diffCount    差异单笔数
 * @param diffAmount   差异金额绝对值合计（分）
 * @param identityOk   六科目恒等式是否平衡
 * @since V6
 */
public record ReconRun(Long batchId, LocalDate reconDate, int localCount, int channelCount,
                       int diffCount, long diffAmount, boolean identityOk) {
}
