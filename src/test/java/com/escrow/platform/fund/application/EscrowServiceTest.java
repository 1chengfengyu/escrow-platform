package com.escrow.platform.fund.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.EscrowDetailStatus;
import com.escrow.platform.fund.domain.EscrowOrderStatus;
import com.escrow.platform.fund.domain.FundEscrowDetail;
import com.escrow.platform.fund.domain.FundEscrowOrder;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.domain.JournalEventType;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 托管服务测试（v2-c5）：整单冻结、按明细抢占退款、明细全终态关闭托管单。
 *
 * @since V2
 */
@ExtendWith(MockitoExtension.class)
class EscrowServiceTest {

    private static final long EMPLOYER_ID = 1001L;

    @Mock
    private FundEscrowOrderMapper fundEscrowOrderMapper;

    @Mock
    private FundEscrowDetailMapper fundEscrowDetailMapper;

    @Mock
    private FundAccountService fundAccountService;

    @Mock
    private JournalService journalService;

    @Mock
    private FundLedgerGuard fundLedgerGuard;

    @InjectMocks
    private EscrowService escrowService;

    /** 明细 / 托管单的条件更新与查询都走 Lambda 构造器，纯单测需先初始化 {@code TableInfo}。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FundEscrowOrder.class);
        TableInfoHelper.initTableInfo(assistant, FundEscrowDetail.class);
    }

    /** 发布托管：1 张托管单 + N 条明细 + 冻结总额 + 1 个 ESCROW_HOLD 批次。 */
    @Test
    void holdShouldCreateOrderDetailsAndJournal() {
        when(fundEscrowOrderMapper.insert(any(FundEscrowOrder.class))).thenAnswer(invocation -> {
            FundEscrowOrder order = invocation.getArgument(0);
            order.setId(9L);
            return 1;
        });
        when(fundAccountService.freeze(EMPLOYER_ID, 10000L))
                .thenReturn(new FundAccountService.Transfer(11L, AccountSubject.ACC_AVAIL,
                        22L, AccountSubject.ACC_FROZEN));

        escrowService.hold(1L, EMPLOYER_ID, 300,
                List.of(new EscrowService.SlotHold(101L, 5000L),
                        new EscrowService.SlotHold(102L, 5000L)),
                "1");

        ArgumentCaptor<FundEscrowOrder> orderCaptor = ArgumentCaptor.forClass(FundEscrowOrder.class);
        verify(fundEscrowOrderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getTaskId()).isEqualTo(1L);
        assertThat(orderCaptor.getValue().getTotalAmount()).isEqualTo(10000L);
        assertThat(orderCaptor.getValue().getFeeRate()).isEqualTo(300);
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(EscrowOrderStatus.ESCROW_ORDER_HELD);

        ArgumentCaptor<FundEscrowDetail> detailCaptor = ArgumentCaptor.forClass(FundEscrowDetail.class);
        verify(fundEscrowDetailMapper, times(2)).insert(detailCaptor.capture());
        assertThat(detailCaptor.getAllValues())
                .extracting(FundEscrowDetail::getSlotId).containsExactly(101L, 102L);
        assertThat(detailCaptor.getAllValues())
                .extracting(FundEscrowDetail::getAmount).containsExactly(5000L, 5000L);
        assertThat(detailCaptor.getAllValues())
                .extracting(FundEscrowDetail::getEscrowOrderId).containsOnly(9L);

        verify(fundAccountService).freeze(EMPLOYER_ID, 10000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JournalService.EntryDraft>> entries = ArgumentCaptor.forClass(List.class);
        verify(journalService).post(eq(JournalBizType.ESCROW_HOLD), eq("1"),
                eq(JournalEventType.ESCROW_HOLD), eq(JournalTriggerType.USER), any(), entries.capture());
        assertThat(entries.getValue()).extracting(JournalService.EntryDraft::direction)
                .containsExactly(JournalDirection.DEBIT, JournalDirection.CREDIT);
    }

    /** 退款只计入成功抢占的明细；明细分批抢不到时按 0 处理。 */
    @Test
    void refundShouldOnlyCountClaimedDetails() {
        FundEscrowOrder order = new FundEscrowOrder();
        order.setId(9L);
        order.setTaskId(1L);
        order.setStatus(EscrowOrderStatus.ESCROW_ORDER_HELD);
        when(fundEscrowOrderMapper.selectOne(any())).thenReturn(order);

        FundEscrowDetail first = detail(91L, 101L, 5000L);
        FundEscrowDetail second = detail(92L, 102L, 5000L);
        when(fundEscrowDetailMapper.selectList(any())).thenReturn(List.of(first, second));
        when(fundEscrowDetailMapper.update(any(), any())).thenReturn(1, 0);
        when(fundAccountService.refund(EMPLOYER_ID, 5000L))
                .thenReturn(new FundAccountService.Transfer(22L, AccountSubject.ACC_FROZEN,
                        11L, AccountSubject.ACC_AVAIL));
        when(fundEscrowDetailMapper.selectCount(any())).thenReturn(0L);

        EscrowService.RefundResult result = escrowService.refund(
                1L, "1:CANCEL", EMPLOYER_ID, List.of(101L, 102L), JournalTriggerType.USER);

        assertThat(result.amount()).isEqualTo(5000L);
        assertThat(result.refundedSlotIds()).containsExactly(101L);
        verify(fundAccountService).refund(EMPLOYER_ID, 5000L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JournalService.EntryDraft>> entries = ArgumentCaptor.forClass(List.class);
        verify(journalService).post(eq(JournalBizType.ESCROW_REFUND), eq("1:CANCEL"),
                eq(JournalEventType.ESCROW_REFUND), eq(JournalTriggerType.USER), any(), entries.capture());
        assertThat(entries.getValue()).extracting(JournalService.EntryDraft::direction)
                .containsExactly(JournalDirection.DEBIT, JournalDirection.CREDIT);

        // 明细全终态 → 托管单关闭（状态由状态机给出，不手写常量）
        verify(fundEscrowOrderMapper).update(any(), any());
    }

    /** 全部明细已退款：本次不搬钱、不记账，也不再推进托管单。 */
    @Test
    void refundWithNothingClaimedShouldBeNoop() {
        FundEscrowOrder order = new FundEscrowOrder();
        order.setId(9L);
        order.setTaskId(1L);
        when(fundEscrowOrderMapper.selectOne(any())).thenReturn(order);
        when(fundEscrowDetailMapper.selectList(any()))
                .thenReturn(List.of(detail(91L, 101L, 5000L)));
        when(fundEscrowDetailMapper.update(any(), any())).thenReturn(0);
        when(fundEscrowDetailMapper.selectCount(any())).thenReturn(1L);

        EscrowService.RefundResult result = escrowService.refund(
                1L, "1:EXPIRE", EMPLOYER_ID, List.of(101L), JournalTriggerType.SYSTEM);

        assertThat(result.amount()).isZero();
        assertThat(result.refundedSlotIds()).isEmpty();
        verify(fundAccountService, never()).refund(any(), anyLong());
        verify(journalService, never()).post(any(), any(), any(), any(), any(), any());
        verify(fundEscrowOrderMapper, never()).update(any(), any());
    }

    /** 任务详情投影用：有托管单返回 total_amount 快照（FR-TASK.DETAIL.01，v2-c6）。 */
    @Test
    void totalAmountOfShouldReturnOrderTotal() {
        FundEscrowOrder order = new FundEscrowOrder();
        order.setTaskId(1L);
        order.setTotalAmount(10_000L);
        when(fundEscrowOrderMapper.selectOne(any())).thenReturn(order);

        assertThat(escrowService.totalAmountOf(1L)).isEqualTo(10_000L);
    }

    /** 草稿未发布、没有托管单：返回 null，不是 0（v2-c6 §4.3）。 */
    @Test
    void totalAmountOfShouldReturnNullWhenNoOrder() {
        when(fundEscrowOrderMapper.selectOne(any())).thenReturn(null);

        assertThat(escrowService.totalAmountOf(1L)).isNull();
    }

    private static FundEscrowDetail detail(Long id, Long slotId, long amount) {
        FundEscrowDetail detail = new FundEscrowDetail();
        detail.setId(id);
        detail.setEscrowOrderId(9L);
        detail.setSlotId(slotId);
        detail.setAmount(amount);
        detail.setStatus(EscrowDetailStatus.ESCROW_DETAIL_HELD);
        return detail;
    }
}
