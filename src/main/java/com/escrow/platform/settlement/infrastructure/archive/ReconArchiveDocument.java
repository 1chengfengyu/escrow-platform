package com.escrow.platform.settlement.infrastructure.archive;

import com.escrow.platform.settlement.domain.ChannelTxn;
import com.escrow.platform.settlement.domain.SettlementDifference;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账报文归档文档（V6，FR-RECON.ARCHIVE.01）。
 *
 * <p>一天一条，{@code id} 就是用对账日生成的字符串：同日重跑对账时文档被覆盖而不是追加，
 * 与 {@code uk_recon_batch_date} 的幂等口径一致。</p>
 *
 * <p>只归档「通道报文 + 差异快照 + 批次汇总」，不存任何账户余额——
 * MongoDB 不承载账务，账务的唯一事实源仍是 MySQL 的账户与分录。</p>
 *
 * @param id           文档 ID，等于 {@code reconDate}
 * @param reconDate    对账日（ISO-8601）
 * @param batchId      对账批次 ID
 * @param localCount   本地打款尝试数
 * @param channelCount 通道流水条数
 * @param diffCount    差异条数
 * @param diffAmount   差异金额合计（分）
 * @param identityOk   六科目恒等式是否平衡
 * @param statement    通道报文快照
 * @param differences  差异快照
 * @param archivedAt   归档时间
 * @since V6
 */
@Document(collection = "recon_archive")
public record ReconArchiveDocument(@Id String id, String reconDate, Long batchId,
                                   int localCount, int channelCount, int diffCount,
                                   long diffAmount, boolean identityOk,
                                   List<StatementSnapshot> statement,
                                   List<DifferenceSnapshot> differences,
                                   LocalDateTime archivedAt) {

    /**
     * 通道报文的一条流水。
     *
     * @param requestNo 通道请求号
     * @param amount    金额（分）
     * @param success   通道是否成功
     */
    public record StatementSnapshot(String requestNo, long amount, boolean success) {
    }

    /**
     * 差异单快照。
     *
     * @param requestNo     本地打款请求号
     * @param diffType      差异类型
     * @param localAmount   本地金额（分）
     * @param channelAmount 通道金额（分）
     */
    public record DifferenceSnapshot(String requestNo, String diffType,
                                     Long localAmount, Long channelAmount) {
    }

    /**
     * 从对账结果组装归档文档。
     *
     * @param date          对账日
     * @param batchId       批次 ID
     * @param localCount    本地尝试数
     * @param statement     通道报文
     * @param differences   差异单
     * @param diffAmount    差异金额合计
     * @param identityOk    恒等式是否平衡
     * @return 归档文档
     */
    public static ReconArchiveDocument of(LocalDate date, Long batchId, int localCount,
                                          List<ChannelTxn> statement,
                                          List<SettlementDifference> differences,
                                          long diffAmount, boolean identityOk) {
        List<StatementSnapshot> statementSnapshots = statement.stream()
                .map(txn -> new StatementSnapshot(txn.requestNo(), txn.amount(), txn.success()))
                .toList();
        List<DifferenceSnapshot> differenceSnapshots = differences.stream()
                .map(difference -> new DifferenceSnapshot(difference.getLocalRecordNo(),
                        difference.getDiffType().name(), difference.getLocalAmount(),
                        difference.getChannelAmount()))
                .toList();
        return new ReconArchiveDocument(date.toString(), date.toString(), batchId, localCount,
                statement.size(), differences.size(), diffAmount, identityOk,
                statementSnapshots, differenceSnapshots, LocalDateTime.now());
    }
}
