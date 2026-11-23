package com.escrow.platform.fund.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.FundJournalBatch;
import com.escrow.platform.fund.domain.FundJournalEntry;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.domain.JournalEventType;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 唯一记账服务测试（v2-c5）：借贷平衡校验与幂等回查。
 *
 * @since V2
 */
@ExtendWith(MockitoExtension.class)
class JournalServiceTest {

    @Mock
    private FundJournalBatchMapper fundJournalBatchMapper;

    @Mock
    private FundJournalEntryMapper fundJournalEntryMapper;

    @InjectMocks
    private JournalService journalService;

    /** 回查已有批次时用 {@code LambdaQueryWrapper}，需先初始化 {@code TableInfo} 缓存。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FundJournalBatch.class);
    }

    /** 借贷相等：写 1 个批次 + 2 条分录，批次号 32 位。 */
    @Test
    void balancedEntriesShouldCreateBatchAndEntries() {
        FundJournalBatch batch = journalService.post(
                JournalBizType.ESCROW_HOLD, "1001", JournalEventType.ESCROW_HOLD,
                JournalTriggerType.USER, "任务发布托管冻结",
                List.of(new JournalService.EntryDraft(10L, AccountSubject.ACC_AVAIL,
                                JournalDirection.DEBIT, 5000L),
                        new JournalService.EntryDraft(20L, AccountSubject.ACC_FROZEN,
                                JournalDirection.CREDIT, 5000L)));

        assertThat(batch.getBizNo()).isEqualTo("1001");
        assertThat(batch.getBizType()).isEqualTo(JournalBizType.ESCROW_HOLD);
        assertThat(batch.getEventType()).isEqualTo(JournalEventType.ESCROW_HOLD);
        assertThat(batch.getBatchNo()).hasSize(32);

        ArgumentCaptor<FundJournalEntry> captor = ArgumentCaptor.forClass(FundJournalEntry.class);
        verify(fundJournalEntryMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(FundJournalEntry::getDirection)
                .containsExactly(JournalDirection.DEBIT, JournalDirection.CREDIT);
        assertThat(captor.getAllValues()).extracting(FundJournalEntry::getAmount)
                .containsExactly(5000L, 5000L);
    }

    /** 借贷不平：直接抛，不写任何行。 */
    @Test
    void unbalancedEntriesShouldBeRejected() {
        List<JournalService.EntryDraft> entries = List.of(
                new JournalService.EntryDraft(10L, AccountSubject.ACC_AVAIL,
                        JournalDirection.DEBIT, 5000L),
                new JournalService.EntryDraft(20L, AccountSubject.ACC_FROZEN,
                        JournalDirection.CREDIT, 4000L));

        assertThatThrownBy(() -> journalService.post(JournalBizType.ESCROW_HOLD, "1001",
                JournalEventType.ESCROW_HOLD, JournalTriggerType.USER, "摘要", entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("借贷不平");

        verify(fundJournalBatchMapper, never()).insert(any(FundJournalBatch.class));
        verify(fundJournalEntryMapper, never()).insert(any(FundJournalEntry.class));
    }

    /** 同幂等键重放：撞唯一键后回查已有批次，不重复写分录。 */
    @Test
    void duplicateBizKeyShouldReturnExistingBatch() {
        FundJournalBatch existing = new FundJournalBatch();
        existing.setId(99L);
        existing.setBizNo("1001");
        when(fundJournalBatchMapper.insert(any(FundJournalBatch.class)))
                .thenThrow(new DuplicateKeyException("uk_fund_journal_batch_biz"));
        when(fundJournalBatchMapper.selectOne(any())).thenReturn(existing);

        FundJournalBatch result = journalService.post(
                JournalBizType.ESCROW_HOLD, "1001", JournalEventType.ESCROW_HOLD,
                JournalTriggerType.USER, "任务发布托管冻结",
                List.of(new JournalService.EntryDraft(10L, AccountSubject.ACC_AVAIL,
                                JournalDirection.DEBIT, 5000L),
                        new JournalService.EntryDraft(20L, AccountSubject.ACC_FROZEN,
                                JournalDirection.CREDIT, 5000L)));

        assertThat(result).isSameAs(existing);
        verify(fundJournalEntryMapper, never()).insert(any(FundJournalEntry.class));
    }

    /** 撞的不是业务幂等键（如 batch_no 冲突）且回查为空：必须原样抛出，不能返回 null。 */
    @Test
    void duplicateWithoutMatchingBizKeyShouldPropagate() {
        when(fundJournalBatchMapper.insert(any(FundJournalBatch.class)))
                .thenThrow(new DuplicateKeyException("uk_fund_journal_batch_no"));
        when(fundJournalBatchMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> journalService.post(
                JournalBizType.ESCROW_HOLD, "1001", JournalEventType.ESCROW_HOLD,
                JournalTriggerType.USER, "任务发布托管冻结",
                List.of(new JournalService.EntryDraft(10L, AccountSubject.ACC_AVAIL,
                                JournalDirection.DEBIT, 5000L),
                        new JournalService.EntryDraft(20L, AccountSubject.ACC_FROZEN,
                                JournalDirection.CREDIT, 5000L))))
                .isInstanceOf(DuplicateKeyException.class);

        verify(fundJournalEntryMapper, never()).insert(any(FundJournalEntry.class));
    }

    /** 金额非正：记账模板写错，直接抛。 */
    @Test
    void nonPositiveAmountShouldBeRejected() {
        assertThatThrownBy(() -> journalService.post(JournalBizType.RECHARGE, "r1",
                JournalEventType.ACCOUNT_ISSUE, JournalTriggerType.USER, "摘要",
                List.of(new JournalService.EntryDraft(10L, AccountSubject.ACC_ISSUED,
                                JournalDirection.DEBIT, 0L),
                        new JournalService.EntryDraft(20L, AccountSubject.ACC_AVAIL,
                                JournalDirection.CREDIT, 0L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 业务单号超 32 字符：不允许落库（列宽 VARCHAR(32)）。 */
    @Test
    void tooLongBizNoShouldBeRejected() {
        assertThatThrownBy(() -> journalService.post(JournalBizType.ESCROW_REFUND, "x".repeat(33),
                JournalEventType.ESCROW_REFUND, JournalTriggerType.USER, "摘要",
                List.of(new JournalService.EntryDraft(10L, AccountSubject.ACC_FROZEN,
                                JournalDirection.DEBIT, 1L),
                        new JournalService.EntryDraft(20L, AccountSubject.ACC_AVAIL,
                                JournalDirection.CREDIT, 1L))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
