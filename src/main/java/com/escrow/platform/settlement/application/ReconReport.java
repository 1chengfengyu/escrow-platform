package com.escrow.platform.settlement.application;

import java.time.LocalDate;

/**
 * 对账日报（V6，FR-RECON.REPORT.01）。
 *
 * <p>期初 = 期末 − 当日发生额。发生额取当日借方合计（借贷恒等，借方合计等于贷方合计），
 * 期末取当前六科目余额合计。</p>
 *
 * @param reconDate    对账日期
 * @param batchId      批次 ID，未跑过为 {@code null}
 * @param status       {@code NOT_RUN} 或批次状态名
 * @param openingTotal 期初余额（分）
 * @param turnover     当日发生额（分）
 * @param closingTotal 期末余额（分）
 * @param diffCount    差异单笔数
 * @param diffAmount   差异金额绝对值合计（分）
 * @param pendingCount 待处理差异单数
 * @param identityOk   六科目恒等式是否平衡
 * @since V6
 */
public record ReconReport(LocalDate reconDate, Long batchId, String status, long openingTotal,
                          long turnover, long closingTotal, int diffCount, long diffAmount,
                          int pendingCount, boolean identityOk) {
}
