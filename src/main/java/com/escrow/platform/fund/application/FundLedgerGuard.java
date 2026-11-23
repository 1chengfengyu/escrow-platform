package com.escrow.platform.fund.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.fund.domain.AccountOwnerType;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.FundAccount;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资金恒等式校验（FR-RECON.BALANCE.01）。
 *
 * <p>{@code ACC_ISSUED = Σ ACC_AVAIL + Σ ACC_FROZEN + ACC_PLATFORM + ACC_TRANSIT + ACC_WITHDRAWN}。
 * 每次资金动作写完凭证后在同一事务内调用；不平即抛异常回滚本次操作。</p>
 *
 * <p>只做全表聚合，账户数量是用户量级而不是流水量级；不做对账差异分析（V7）。</p>
 *
 * @since V4
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundLedgerGuard {

    private final FundAccountMapper fundAccountMapper;

    /**
     * 校验六科目恒等式。
     *
     * @param scene 场景标识（日志用，如 ACCEPT / PAYOUT / RECHARGE）
     * @param bizNo 业务单据号（日志用）
     * @throws IllegalStateException 恒等式不平衡（事务必须回滚）
     */
    public void assertBalanced(String scene, String bizNo) {
        Totals totals = totals();
        if (!totals.balanced()) {
            log.error("资金恒等式不平衡 scene={} bizNo={} issued={} total={} user={} system={}",
                    scene, bizNo, totals.issued(), totals.total(), totals.user(), totals.system());
            throw new IllegalStateException("资金恒等式不平衡 scene=" + scene + " bizNo=" + bizNo
                    + " issued=" + totals.issued() + " total=" + totals.total());
        }
        log.debug("资金恒等式通过 scene={} bizNo={} total={}", scene, bizNo, totals.total());
    }

    /**
     * 只判断恒等式是否平衡，不抛异常（对账批次把结果落成 {@code identity_ok} 用）。
     *
     * @return 六科目是否平衡
     */
    public boolean isBalanced() {
        return totals().balanced();
    }

    /**
     * 六科目余额合计（平衡时等于 ACC_ISSUED）。
     *
     * @return 全部账户余额之和（分）
     */
    public long totalBalance() {
        return totals().total();
    }

    private Totals totals() {
        List<FundAccount> accounts = fundAccountMapper.selectList(
                new LambdaQueryWrapper<FundAccount>()
                        .select(FundAccount::getSubject, FundAccount::getBalance,
                                FundAccount::getOwnerType));
        long issued = 0L;
        long userTotal = 0L;
        long systemTotal = 0L;
        for (FundAccount account : accounts) {
            if (account.getBalance() == null) {
                continue;
            }
            if (account.getSubject() == AccountSubject.ACC_ISSUED) {
                issued = Math.addExact(issued, account.getBalance());
            } else if (account.getOwnerType() == AccountOwnerType.USER) {
                userTotal = Math.addExact(userTotal, account.getBalance());
            } else {
                systemTotal = Math.addExact(systemTotal, account.getBalance());
            }
        }
        return new Totals(issued, userTotal, systemTotal);
    }

    private record Totals(long issued, long user, long system) {

        long total() {
            return Math.addExact(user, system);
        }

        boolean balanced() {
            return issued == total();
        }
    }
}
