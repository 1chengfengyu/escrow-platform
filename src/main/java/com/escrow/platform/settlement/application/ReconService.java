package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.escrow.platform.fund.application.FundLedgerGuard;
import com.escrow.platform.fund.domain.FundJournalEntry;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import com.escrow.platform.settlement.domain.ChannelTxn;
import com.escrow.platform.settlement.domain.DifferenceStatus;
import com.escrow.platform.settlement.domain.DifferenceType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.ReconBatch;
import com.escrow.platform.settlement.domain.ReconBatchStatus;
import com.escrow.platform.settlement.domain.SettlementDifference;
import com.escrow.platform.settlement.infrastructure.archive.ReconArchiveDocument;
import com.escrow.platform.settlement.infrastructure.archive.ReconArchiveStore;
import com.escrow.platform.settlement.infrastructure.channel.ChannelStatementSource;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.ReconBatchMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementDifferenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 日终对账：质量对比本地打款尝试与通道流水，按四类差异落 {@code settlement_difference}（V6）。
 *
 * <p><b>按日期幂等</b>：{@code uk_recon_batch_date} 保证一天一个批次；重复执行返回既有批次。
 * 差异单落库也靠唯一键去重，重跑不会重复计数。</p>
 *
 * <p><b>只识别、不处置</b>：本类不写任何分录、不改打款单状态；
 * 四类处置在 {@code ReconHandlingService}（v6-c3），处置前后都由恒等式兜底。</p>
 *
 * <p><b>差异分类口径</b>（SRS 3.4.6）：
 * 本地成功 + 通道无记录 → {@link DifferenceType#CHANNEL_MISSING}；
 * 本地未知 + 通道成功 → {@link DifferenceType#LOST_RESPONSE}；
 * 双方成功但金额不同 → {@link DifferenceType#AMOUNT_MISMATCH}；
 * 本地未知且通道也无记录 → {@link DifferenceType#RESULT_UNDETERMINED}。
 * 四类不得合并成一类。</p>
 *
 * <p>本类不加 {@code @Transactional}：通道流水是外部数据，先取数据再逐条落库，
 * 避免把外部调用包进事务。</p>
 *
 * @since V6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconService {

    private final ReconBatchMapper reconBatchMapper;
    private final PayoutOrderMapper payoutOrderMapper;
    private final SettlementDifferenceMapper settlementDifferenceMapper;
    private final FundJournalEntryMapper fundJournalEntryMapper;
    private final ChannelStatementSource channelStatementSource;
    private final FundLedgerGuard fundLedgerGuard;
    private final ReconArchiveStore reconArchiveStore;

    /**
     * 执行指定日期的对账（FR-RECON.JOB.01）。
     *
     * @param date 对账日期
     * @return 批次结果；当天已对完直接返回既有结果
     */
    public ReconRun runDaily(LocalDate date) {
        Objects.requireNonNull(date, "reconDate");
        ReconBatch batch = startBatch(date);
        if (batch.getStatus() == ReconBatchStatus.RECON_DONE) {
            log.info("对账日期已完成，直接返回既有结果 date={} batchId={}", date, batch.getId());
            return toRun(batch);
        }
        try {
            List<PayoutOrder> attempts = payoutOrderMapper.selectList(
                    new LambdaQueryWrapper<PayoutOrder>()
                            .ge(PayoutOrder::getCreatedAt, date.atStartOfDay())
                            .lt(PayoutOrder::getCreatedAt, date.plusDays(1).atStartOfDay()));
            List<ChannelTxn> statement = channelStatementSource.statementOf(date);
            Map<String, ChannelTxn> channelByNo = new HashMap<>(statement.size() * 2);
            for (ChannelTxn txn : statement) {
                channelByNo.put(txn.requestNo(), txn);
            }
            List<SettlementDifference> differences = detect(date, attempts, channelByNo);
            for (SettlementDifference difference : differences) {
                insertQuietly(difference);
            }
            long diffAmount = differences.stream().mapToLong(ReconService::differenceAmount).sum();
            boolean identityOk = fundLedgerGuard.isBalanced();
            finishBatch(batch.getId(), attempts.size(), statement.size(), differences.size(),
                    diffAmount, identityOk);
            log.info("对账完成 date={} local={} channel={} diff={} diffAmount={} identityOk={}",
                    date, attempts.size(), statement.size(), differences.size(), diffAmount, identityOk);
            archiveQuietly(date, batch.getId(), attempts.size(), statement, differences,
                    diffAmount, identityOk);
            return new ReconRun(batch.getId(), date, attempts.size(), statement.size(),
                    differences.size(), diffAmount, identityOk);
        } catch (RuntimeException ex) {
            markFailed(batch.getId());
            throw ex;
        }
    }

    /**
     * 对账日报（FR-RECON.REPORT.01）：期初 / 发生 / 期末 + 差异概况。
     *
     * @param date 对账日期
     * @return 日报；当天没跑过对账时 {@code status = NOT_RUN}
     */
    public ReconReport reportOf(LocalDate date) {
        Objects.requireNonNull(date, "reconDate");
        ReconBatch batch = reconBatchMapper.selectOne(new LambdaQueryWrapper<ReconBatch>()
                .eq(ReconBatch::getReconDate, date));
        long turnover = fundJournalEntryMapper.selectList(new LambdaQueryWrapper<FundJournalEntry>()
                        .ge(FundJournalEntry::getCreatedAt, date.atStartOfDay())
                        .lt(FundJournalEntry::getCreatedAt, date.plusDays(1).atStartOfDay())
                        .eq(FundJournalEntry::getDirection, JournalDirection.DEBIT))
                .stream()
                .mapToLong(entry -> entry.getAmount() == null ? 0L : entry.getAmount())
                .sum();
        long closing = fundLedgerGuard.totalBalance();
        long opening = closing - turnover;
        Long pending = settlementDifferenceMapper.selectCount(
                new LambdaQueryWrapper<SettlementDifference>()
                        .eq(SettlementDifference::getReconDate, date)
                        .eq(SettlementDifference::getStatus, DifferenceStatus.SETTLEMENT_DIFF_PENDING));
        return new ReconReport(date,
                batch == null ? null : batch.getId(),
                batch == null ? "NOT_RUN" : batch.getStatus().name(),
                opening, turnover, closing,
                batch == null ? 0 : nullSafe(batch.getDiffCount()),
                batch == null ? 0L : nullSafe(batch.getDiffAmount()),
                pending == null ? 0 : pending.intValue(),
                batch != null && Integer.valueOf(1).equals(batch.getIdentityOk()));
    }

    /**
     * 归档报文：失败只告警，不影响对账结果（归档是审计副本，不是账务事实源）。
     */
    private void archiveQuietly(LocalDate date, Long batchId, int localCount,
                                List<ChannelTxn> statement,
                                List<SettlementDifference> differences,
                                long diffAmount, boolean identityOk) {
        try {
            reconArchiveStore.archive(ReconArchiveDocument.of(date, batchId, localCount,
                    statement, differences, diffAmount, identityOk));
        } catch (RuntimeException ex) {
            log.warn("对账报文归档失败，已忽略 date={} batchId={}", date, batchId, ex);
        }
    }

    private ReconBatch startBatch(LocalDate date) {
        ReconBatch batch = new ReconBatch();
        batch.setReconDate(date);
        batch.setStatus(ReconBatchStatus.RECON_RUNNING);
        batch.setLocalCount(0);
        batch.setChannelCount(0);
        batch.setDiffCount(0);
        batch.setDiffAmount(0L);
        batch.setIdentityOk(1);
        batch.setCreatedAt(LocalDateTime.now());
        try {
            reconBatchMapper.insert(batch);
            return batch;
        } catch (DuplicateKeyException ex) {
            ReconBatch existing = reconBatchMapper.selectOne(new LambdaQueryWrapper<ReconBatch>()
                    .eq(ReconBatch::getReconDate, date));
            if (existing == null) {
                throw ex;
            }
            if (existing.getStatus() != ReconBatchStatus.RECON_RUNNING) {
                int rows = reconBatchMapper.update(null, new LambdaUpdateWrapper<ReconBatch>()
                        .eq(ReconBatch::getId, existing.getId())
                        .in(ReconBatch::getStatus, ReconBatchStatus.RECON_DONE,
                                ReconBatchStatus.RECON_FAILED)
                        .set(ReconBatch::getStatus, ReconBatchStatus.RECON_RUNNING)
                        .set(ReconBatch::getLocalCount, 0)
                        .set(ReconBatch::getChannelCount, 0)
                        .set(ReconBatch::getDiffCount, 0)
                        .set(ReconBatch::getDiffAmount, 0L)
                        .set(ReconBatch::getIdentityOk, 1)
                        .set(ReconBatch::getFinishedAt, null));
                if (rows == 1) {
                    existing.setStatus(ReconBatchStatus.RECON_RUNNING);
                }
            }
            return existing;
        }
    }

    private List<SettlementDifference> detect(LocalDate date, List<PayoutOrder> attempts,
                                              Map<String, ChannelTxn> channelByNo) {
        List<SettlementDifference> differences = new ArrayList<>();
        for (PayoutOrder attempt : attempts) {
            ChannelTxn txn = channelByNo.get(attempt.getRequestNo());
            long localAmount = attempt.getActualAmount() != null
                    ? attempt.getActualAmount() : nullSafe(attempt.getAmount());
            if (attempt.getStatus() == PayoutStatus.PAYOUT_SUCCESS) {
                if (txn == null || !txn.success()) {
                    differences.add(difference(date, DifferenceType.CHANNEL_MISSING,
                            attempt, localAmount, null));
                } else if (txn.amount() != localAmount) {
                    differences.add(difference(date, DifferenceType.AMOUNT_MISMATCH,
                            attempt, localAmount, txn.amount()));
                }
            } else if (attempt.getStatus() == PayoutStatus.PAYOUT_PENDING_VERIFICATION) {
                if (txn != null && txn.success()) {
                    differences.add(difference(date, DifferenceType.LOST_RESPONSE,
                            attempt, null, txn.amount()));
                } else {
                    differences.add(difference(date, DifferenceType.RESULT_UNDETERMINED,
                            attempt, nullSafe(attempt.getAmount()), null));
                }
            }
        }
        return differences;
    }

    private SettlementDifference difference(LocalDate date, DifferenceType type, PayoutOrder attempt,
                                            Long localAmount, Long channelAmount) {
        SettlementDifference difference = new SettlementDifference();
        difference.setReconDate(date);
        difference.setDiffType(type);
        difference.setLocalRecordNo(attempt.getRequestNo());
        difference.setChannelRecordNo(channelAmount == null ? null : attempt.getRequestNo());
        difference.setLocalAmount(localAmount);
        difference.setChannelAmount(channelAmount);
        difference.setStatus(DifferenceStatus.SETTLEMENT_DIFF_PENDING);
        difference.setCreatedAt(LocalDateTime.now());
        return difference;
    }

    private void insertQuietly(SettlementDifference difference) {
        try {
            settlementDifferenceMapper.insert(difference);
        } catch (DuplicateKeyException ex) {
            log.info("差异单已存在，跳过 reconDate={} requestNo={} type={}",
                    difference.getReconDate(), difference.getLocalRecordNo(), difference.getDiffType());
        }
    }

    private void finishBatch(Long batchId, int localCount, int channelCount, int diffCount,
                             long diffAmount, boolean identityOk) {
        reconBatchMapper.update(null, new LambdaUpdateWrapper<ReconBatch>()
                .eq(ReconBatch::getId, batchId)
                .eq(ReconBatch::getStatus, ReconBatchStatus.RECON_RUNNING)
                .set(ReconBatch::getStatus, ReconBatchStatus.RECON_DONE)
                .set(ReconBatch::getLocalCount, localCount)
                .set(ReconBatch::getChannelCount, channelCount)
                .set(ReconBatch::getDiffCount, diffCount)
                .set(ReconBatch::getDiffAmount, diffAmount)
                .set(ReconBatch::getIdentityOk, identityOk ? 1 : 0)
                .set(ReconBatch::getFinishedAt, LocalDateTime.now()));
    }

    private void markFailed(Long batchId) {
        reconBatchMapper.update(null, new LambdaUpdateWrapper<ReconBatch>()
                .eq(ReconBatch::getId, batchId)
                .eq(ReconBatch::getStatus, ReconBatchStatus.RECON_RUNNING)
                .set(ReconBatch::getStatus, ReconBatchStatus.RECON_FAILED)
                .set(ReconBatch::getFinishedAt, LocalDateTime.now()));
    }

    private static ReconRun toRun(ReconBatch batch) {
        return new ReconRun(batch.getId(), batch.getReconDate(),
                nullSafe(batch.getLocalCount()), nullSafe(batch.getChannelCount()),
                nullSafe(batch.getDiffCount()), nullSafe(batch.getDiffAmount()),
                Integer.valueOf(1).equals(batch.getIdentityOk()));
    }

    private static long differenceAmount(SettlementDifference difference) {
        Long local = difference.getLocalAmount();
        Long channel = difference.getChannelAmount();
        if (local != null && channel != null) {
            return Math.abs(local - channel);
        }
        if (local != null) {
            return local;
        }
        return channel == null ? 0L : channel;
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
