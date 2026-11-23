package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账差异识别单测（V6，FR-RECON.JOB.01 / DIFF.01）。
 *
 * <p>四类差异必须分别落库，不得合并成 AMOUNT_MISMATCH；同日重跑不重复落差异。</p>
 *
 * @since V6
 */
@ExtendWith(MockitoExtension.class)
class ReconServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 18);

    @Mock
    private ReconBatchMapper reconBatchMapper;
    @Mock
    private PayoutOrderMapper payoutOrderMapper;
    @Mock
    private SettlementDifferenceMapper settlementDifferenceMapper;
    @Mock
    private FundJournalEntryMapper fundJournalEntryMapper;
    @Mock
    private ChannelStatementSource channelStatementSource;
    @Mock
    private FundLedgerGuard fundLedgerGuard;
    @Mock
    private ReconArchiveStore reconArchiveStore;

    @InjectMocks
    private ReconService reconService;

    /** 纯单测没有 MyBatis 上下文，Lambda 列名解析需要显式初始化表元数据。 */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ReconBatch.class);
        TableInfoHelper.initTableInfo(assistant, PayoutOrder.class);
        TableInfoHelper.initTableInfo(assistant, SettlementDifference.class);
        TableInfoHelper.initTableInfo(assistant, FundJournalEntry.class);
    }

    /** 四类差异分别识别：本地成功通道缺失、金额不一致、本地未知通道成功、双方都未知。 */
    @Test
    void runDailyShouldDetectFourDifferenceTypes() {
        when(payoutOrderMapper.selectList(any())).thenReturn(List.of(
                payout("PO-1", PayoutStatus.PAYOUT_SUCCESS, 1_000L, 1_000L),
                payout("PO-2", PayoutStatus.PAYOUT_SUCCESS, 1_000L, 1_000L),
                payout("PO-3", PayoutStatus.PAYOUT_PENDING_VERIFICATION, 1_000L, null),
                payout("PO-4", PayoutStatus.PAYOUT_PENDING_VERIFICATION, 1_000L, null)));
        when(channelStatementSource.statementOf(DATE)).thenReturn(List.of(
                new ChannelTxn("PO-2", 900L, true),
                new ChannelTxn("PO-3", 1_000L, true)));
        when(fundLedgerGuard.isBalanced()).thenReturn(true);

        ReconRun run = reconService.runDaily(DATE);

        ArgumentCaptor<SettlementDifference> captor =
                ArgumentCaptor.forClass(SettlementDifference.class);
        verify(settlementDifferenceMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(SettlementDifference::getDiffType)
                .containsExactlyInAnyOrder(DifferenceType.CHANNEL_MISSING,
                        DifferenceType.AMOUNT_MISMATCH, DifferenceType.LOST_RESPONSE,
                        DifferenceType.RESULT_UNDETERMINED);
        assertThat(captor.getAllValues()).allSatisfy(difference ->
                assertThat(difference.getStatus()).isEqualTo(DifferenceStatus.SETTLEMENT_DIFF_PENDING));
        assertThat(run.localCount()).isEqualTo(4);
        assertThat(run.channelCount()).isEqualTo(2);
        assertThat(run.diffCount()).isEqualTo(4);
        assertThat(run.diffAmount()).isEqualTo(1_000L + 100L + 1_000L + 1_000L);
        assertThat(run.identityOk()).isTrue();

        ArgumentCaptor<ReconArchiveDocument> archiveCaptor =
                ArgumentCaptor.forClass(ReconArchiveDocument.class);
        verify(reconArchiveStore).archive(archiveCaptor.capture());
        assertThat(archiveCaptor.getValue().id()).isEqualTo(DATE.toString());
        assertThat(archiveCaptor.getValue().channelCount()).isEqualTo(2);
        assertThat(archiveCaptor.getValue().differences()).hasSize(4);
    }

    /** 归档失败不能拖垮对账：结果照常返回，批次照常完成。 */
    @Test
    void archiveFailureShouldNotBreakRecon() {
        when(payoutOrderMapper.selectList(any())).thenReturn(List.of());
        when(channelStatementSource.statementOf(DATE)).thenReturn(List.of());
        when(fundLedgerGuard.isBalanced()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("mongo down"))
                .when(reconArchiveStore).archive(any());

        ReconRun run = reconService.runDaily(DATE);

        assertThat(run.diffCount()).isZero();
        assertThat(run.identityOk()).isTrue();
    }

    /** 无差异的正常日：只落批次，不落差异单。 */
    @Test
    void cleanDayShouldProduceNoDifference() {
        when(payoutOrderMapper.selectList(any())).thenReturn(List.of(
                payout("PO-1", PayoutStatus.PAYOUT_SUCCESS, 1_000L, 1_000L)));
        when(channelStatementSource.statementOf(DATE)).thenReturn(List.of(
                new ChannelTxn("PO-1", 1_000L, true)));
        when(fundLedgerGuard.isBalanced()).thenReturn(true);

        ReconRun run = reconService.runDaily(DATE);

        verify(settlementDifferenceMapper, never()).insert(any(SettlementDifference.class));
        assertThat(run.diffCount()).isZero();
        assertThat(run.identityOk()).isTrue();
    }

    /** 同日重复执行：命中唯一键后返回既有 DONE 批次，不再识别差异。 */
    @Test
    void duplicateDateShouldReturnExistingBatch() {
        when(reconBatchMapper.insert(any(ReconBatch.class)))
                .thenThrow(new DuplicateKeyException("uk_recon_batch_date"));
        ReconBatch existing = new ReconBatch();
        existing.setId(88L);
        existing.setReconDate(DATE);
        existing.setStatus(ReconBatchStatus.RECON_DONE);
        existing.setDiffCount(2);
        existing.setDiffAmount(300L);
        existing.setIdentityOk(1);
        when(reconBatchMapper.selectOne(any())).thenReturn(existing);

        ReconRun run = reconService.runDaily(DATE);

        assertThat(run.batchId()).isEqualTo(88L);
        assertThat(run.diffCount()).isEqualTo(2);
        verify(payoutOrderMapper, never()).selectList(any());
        verify(channelStatementSource, never()).statementOf(any());
    }

    /** 通道流水异常：批次置 FAILED 后原样抛出，允许同日重跑。 */
    @Test
    void statementFailureShouldMarkBatchFailedAndRethrow() {
        when(payoutOrderMapper.selectList(any())).thenReturn(List.of());
        when(channelStatementSource.statementOf(DATE))
                .thenThrow(new IllegalStateException("channel down"));

        assertThatThrownBy(() -> reconService.runDaily(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel down");
        verify(reconBatchMapper, org.mockito.Mockito.times(1)).update(any(), any());
    }

    /** 日报：期初 = 期末 − 当日发生额；待处理差异单单独计数。 */
    @Test
    void reportShouldComputeOpeningTurnoverAndClosing() {
        ReconBatch batch = new ReconBatch();
        batch.setId(7L);
        batch.setReconDate(DATE);
        batch.setStatus(ReconBatchStatus.RECON_DONE);
        batch.setDiffCount(3);
        batch.setDiffAmount(500L);
        batch.setIdentityOk(1);
        when(reconBatchMapper.selectOne(any())).thenReturn(batch);
        when(fundJournalEntryMapper.selectList(any())).thenReturn(List.of(
                entry(JournalDirection.DEBIT, 3_000L),
                entry(JournalDirection.DEBIT, 2_000L)));
        when(fundLedgerGuard.totalBalance()).thenReturn(93_000L);
        when(settlementDifferenceMapper.selectCount(any())).thenReturn(2L);

        ReconReport report = reconService.reportOf(DATE);

        assertThat(report.turnover()).isEqualTo(5_000L);
        assertThat(report.closingTotal()).isEqualTo(93_000L);
        assertThat(report.openingTotal()).isEqualTo(88_000L);
        assertThat(report.pendingCount()).isEqualTo(2);
        assertThat(report.status()).isEqualTo("RECON_DONE");
        assertThat(report.identityOk()).isTrue();
    }

    private static PayoutOrder payout(String requestNo, PayoutStatus status, long amount,
                                      Long actualAmount) {
        PayoutOrder order = new PayoutOrder();
        order.setId((long) requestNo.hashCode());
        order.setRequestNo(requestNo);
        order.setStatus(status);
        order.setAmount(amount);
        order.setActualAmount(actualAmount);
        order.setCreatedAt(LocalDateTime.of(2026, 11, 18, 10, 0));
        return order;
    }

    private static FundJournalEntry entry(JournalDirection direction, long amount) {
        FundJournalEntry entry = new FundJournalEntry();
        entry.setDirection(direction);
        entry.setAmount(amount);
        return entry;
    }
}
