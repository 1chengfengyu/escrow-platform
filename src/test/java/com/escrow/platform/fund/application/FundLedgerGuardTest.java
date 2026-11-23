package com.escrow.platform.fund.application;

import com.escrow.platform.fund.domain.AccountOwnerType;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.FundAccount;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 资金恒等式测试（FR-RECON.BALANCE.01，v4-c3）。
 *
 * @since V4
 */
class FundLedgerGuardTest {

    private final FundAccountMapper mapper = mock(FundAccountMapper.class);
    private final FundLedgerGuard guard = new FundLedgerGuard(mapper);

    /** 六科目：发行 = 可用 + 冻结 + 平台 + 在途 + 已提现，应通过。 */
    @Test
    void balancedAccountsShouldPass() {
        when(mapper.selectList(any())).thenReturn(List.of(
                account(AccountOwnerType.SYSTEM, AccountSubject.ACC_ISSUED, 100_000),
                account(AccountOwnerType.USER, AccountSubject.ACC_AVAIL, 30_000),
                account(AccountOwnerType.USER, AccountSubject.ACC_FROZEN, 40_000),
                account(AccountOwnerType.SYSTEM, AccountSubject.ACC_PLATFORM, 10_000),
                account(AccountOwnerType.SYSTEM, AccountSubject.ACC_TRANSIT, 15_000),
                account(AccountOwnerType.SYSTEM, AccountSubject.ACC_WITHDRAWN, 5_000)));

        guard.assertBalanced("TEST", "B1");
    }

    /** 任意科目少 1 分都必须抛异常，由事务回滚。 */
    @Test
    void unbalancedAccountsShouldThrow() {
        when(mapper.selectList(any())).thenReturn(List.of(
                account(AccountOwnerType.SYSTEM, AccountSubject.ACC_ISSUED, 100_000),
                account(AccountOwnerType.USER, AccountSubject.ACC_AVAIL, 99_999)));

        assertThatThrownBy(() -> guard.assertBalanced("TEST", "B1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("资金恒等式不平衡");
    }

    /** 空账本（尚无任何充值）视为平衡。 */
    @Test
    void emptyLedgerShouldPass() {
        when(mapper.selectList(any())).thenReturn(List.of());

        guard.assertBalanced("TEST", "EMPTY");
    }

    private static FundAccount account(AccountOwnerType owner, AccountSubject subject, long balance) {
        FundAccount account = new FundAccount();
        account.setOwnerType(owner);
        account.setSubject(subject);
        account.setBalance(balance);
        return account;
    }
}
