package com.escrow.platform.fund.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.fund.api.FundBalanceView;
import com.escrow.platform.fund.domain.AccountOwnerType;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.FundAccount;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.domain.JournalEventType;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 资金账户服务测试（v2-c5）：条件更新成败的映射、充值的限额与流水。
 *
 * <p>并发本身由环境门控的集成测试验证；这里只验证「受影响行数 → 业务码」的映射，
 * 它是并发语义在应用层的唯一出口。</p>
 *
 * @since V2
 */
@ExtendWith(MockitoExtension.class)
class FundAccountServiceTest {

    @Mock
    private FundAccountMapper fundAccountMapper;

    @Mock
    private JournalService journalService;

    @Mock
    private FundLedgerGuard fundLedgerGuard;

    @InjectMocks
    private FundAccountService fundAccountService;

    /** Lambda 条件构造器需要 {@code TableInfo} 缓存（纯单测没有 MyBatis 启动过程）。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FundAccount.class);
    }

    /** 冻结成功：可用扣减 1 行 + 冻结增加 1 行。 */
    @Test
    void freezeShouldMoveBalanceWhenConditionalUpdateHits() {
        FundAccount avail = account(11L, AccountSubject.ACC_AVAIL, 5000L);
        when(fundAccountMapper.update(any(), any())).thenReturn(1, 1);
        when(fundAccountMapper.selectOne(any())).thenReturn(null, avail);
        when(fundAccountMapper.insert(any(FundAccount.class))).thenAnswer(invocation -> {
            FundAccount inserted = invocation.getArgument(0);
            inserted.setId(22L);
            return 1;
        });

        FundAccountService.Transfer transfer = fundAccountService.freeze(1001L, 5000L);

        assertThat(transfer.debitAccountId()).isEqualTo(11L);
        assertThat(transfer.debitSubject()).isEqualTo(AccountSubject.ACC_AVAIL);
        assertThat(transfer.creditAccountId()).isEqualTo(22L);
        assertThat(transfer.creditSubject()).isEqualTo(AccountSubject.ACC_FROZEN);
        verify(fundAccountMapper, times(2)).update(any(), any());
    }

    /** 冻结时可用余额不足：0 行 → 409 INSUFFICIENT_BALANCE，且不创建冻结账户。 */
    @Test
    void freezeShouldFailWhenNoRowUpdated() {
        when(fundAccountMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> fundAccountService.freeze(1001L, 5000L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

        verify(fundAccountMapper, times(1)).update(any(), any());
        verify(fundAccountMapper, never()).insert(any(FundAccount.class));
    }

    /** 退款成功：冻结扣减 → 可用增加。 */
    @Test
    void refundShouldMoveBalanceBackToAvailable() {
        FundAccount frozen = account(22L, AccountSubject.ACC_FROZEN, 5000L);
        when(fundAccountMapper.update(any(), any())).thenReturn(1, 1);
        when(fundAccountMapper.selectOne(any())).thenReturn(null, frozen);
        when(fundAccountMapper.insert(any(FundAccount.class))).thenAnswer(invocation -> {
            FundAccount inserted = invocation.getArgument(0);
            inserted.setId(11L);
            return 1;
        });

        FundAccountService.Transfer transfer = fundAccountService.refund(1001L, 5000L);

        assertThat(transfer.debitAccountId()).isEqualTo(22L);
        assertThat(transfer.debitSubject()).isEqualTo(AccountSubject.ACC_FROZEN);
        assertThat(transfer.creditAccountId()).isEqualTo(11L);
        assertThat(transfer.creditSubject()).isEqualTo(AccountSubject.ACC_AVAIL);
    }

    /** 退款时冻结余额不足：说明账实已不一致，抛 409 INVALID_STATE_TRANSITION。 */
    @Test
    void refundShouldFailWhenFrozenBalanceNotEnough() {
        when(fundAccountMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> fundAccountService.refund(1001L, 5000L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    /** 充值成功：加余额 + 写一个 ACCOUNT_ISSUE 批次（借发行 / 贷可用）。 */
    @Test
    void rechargeShouldIncreaseBalanceAndWriteJournal() {
        FundAccount avail = account(11L, AccountSubject.ACC_AVAIL, 5000L);
        FundAccount issued = account(33L, AccountSubject.ACC_ISSUED, 0L);
        when(fundAccountMapper.selectOne(any())).thenReturn(avail, issued, avail, null);
        when(fundAccountMapper.update(any(), any())).thenReturn(1);

        FundBalanceView view = fundAccountService.recharge(1001L, 5000L);

        assertThat(view.available()).isEqualTo(5000L);
        assertThat(view.frozen()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JournalService.EntryDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(journalService).post(eq(JournalBizType.RECHARGE), any(), eq(JournalEventType.ACCOUNT_ISSUE),
                eq(JournalTriggerType.USER), any(), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).subject()).isEqualTo(AccountSubject.ACC_ISSUED);
        assertThat(captor.getValue().get(0).direction()).isEqualTo(JournalDirection.DEBIT);
        assertThat(captor.getValue().get(1).subject()).isEqualTo(AccountSubject.ACC_AVAIL);
        assertThat(captor.getValue().get(1).direction()).isEqualTo(JournalDirection.CREDIT);
    }

    /** 超过单笔上限：409 RECHARGE_LIMIT_EXCEEDED，不碰数据库。 */
    @Test
    void rechargeAboveLimitShouldBeRejected() {
        assertThatThrownBy(() -> fundAccountService.recharge(1001L,
                FundAccountService.MAX_RECHARGE_AMOUNT + 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECHARGE_LIMIT_EXCEEDED));
        verifyNoInteractions(fundAccountMapper, journalService);
    }

    /** 非正金额：400 INVALID_PARAMETER。 */
    @Test
    void rechargeWithNonPositiveAmountShouldBeRejected() {
        assertThatThrownBy(() -> fundAccountService.recharge(1001L, 0L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verifyNoInteractions(fundAccountMapper, journalService);
    }

    /** 未登录：401 UNAUTHENTICATED（Controller 的兜底之外再加一道）。 */
    @Test
    void rechargeWithoutUserShouldBeRejected() {
        assertThatThrownBy(() -> fundAccountService.recharge(null, 100L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        verify(fundAccountMapper, never()).update(any(), any());
        verify(journalService, never()).post(any(), any(), any(), any(), any(), any());
    }

    private static FundAccount account(Long id, AccountSubject subject, long balance) {
        FundAccount account = new FundAccount();
        account.setId(id);
        account.setOwnerType(subject == AccountSubject.ACC_ISSUED
                ? AccountOwnerType.SYSTEM : AccountOwnerType.USER);
        account.setUserId(subject == AccountSubject.ACC_ISSUED ? null : 1001L);
        account.setSubject(subject);
        account.setBalance(balance);
        return account;
    }
}
