package com.escrow.platform.fund.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 资金账户读写与模拟充值（FR-WALLET.ISSUE.01 / RECHARGE.01~.02，FR-ESCROW.HOLD.01~.04）。
 *
 * <p><b>并发口径（v2-c5 裁决 1B）</b>：冻结 / 退款一律用条件更新
 * {@code UPDATE ... SET balance = balance ± ? WHERE ... AND balance >= ?}，
 * 以受影响行数判断成败，不启用 {@code version} 乐观锁，也不引 Redisson——
 * V2 单库单实例，条件更新 + 库层 {@code ck_fund_account_balance} 已是双保险。</p>
 *
 * <p>账户按需创建（10A）：先查、无则插，插入撞唯一键（并发）则回查。
 * 生成列 {@code owner_user_id} / {@code system_subject} 不映射进实体，查询用普通列。</p>
 *
 * <p>记账只经 {@link JournalService}；本类只改余额，不写分录。</p>
 *
 * @since V2
 */
@Service
@RequiredArgsConstructor
public class FundAccountService {

    /** 模拟充值单笔上限：50,000 元 = 5,000,000 分（FR-WALLET.RECHARGE.01）。 */
    public static final long MAX_RECHARGE_AMOUNT = 5_000_000L;

    private static final String INVALID_AMOUNT_MESSAGE = "金额必须大于 0";
    private static final String INSUFFICIENT_MESSAGE = "可用余额不足";
    private static final String FROZEN_NOT_ENOUGH_MESSAGE = "冻结余额不足，无法退款";
    private static final String TRANSIT_NOT_ENOUGH_MESSAGE = "在途余额不足，无法结算";
    private static final String RECHARGE_LIMIT_MESSAGE = "单笔充值不能超过 50,000 元";
    private static final String UNAUTHENTICATED_MESSAGE = "未认证或登录已失效";

    private final FundAccountMapper fundAccountMapper;
    private final JournalService journalService;
    private final FundLedgerGuard fundLedgerGuard;

    /**
     * 一次余额搬运涉及的两个账户（借方 = 流出，贷方 = 流入）。
     *
     * @param debitAccountId  流出账户 ID
     * @param debitSubject    流出科目
     * @param creditAccountId 流入账户 ID
     * @param creditSubject   流入科目
     */
    public record Transfer(Long debitAccountId, AccountSubject debitSubject,
                           Long creditAccountId, AccountSubject creditSubject) {
    }

    /**
     * 打款结算的一笔贷方。
     *
     * @param accountId 账户 ID
     * @param subject   科目
     * @param amount    金额（分）
     */
    public record SettlementLeg(Long accountId, AccountSubject subject, long amount) {
    }

    /**
     * 打款成功后的资金结果：在途账户整体扣减，贷方按「劳动者实付 + 平台服务费 + 雇主差额」拆分。
     *
     * @param transitAccountId 在途账户 ID（借方）
     * @param transitAmount    在途扣减总额（应结金额 S）
     * @param credits          贷方明细（金额之和必须等于 {@code transitAmount}）
     */
    public record PayoutSettlement(Long transitAccountId, long transitAmount,
                                   List<SettlementLeg> credits) {
    }

    /**
     * 按需获取用户账户；不存在则插入余额为 0 的账户。
     *
     * @param userId  用户 ID
     * @param subject 科目，USER 只允许 ACC_AVAIL / ACC_FROZEN
     * @return 账户实体
     */
    public FundAccount getOrCreateUserAccount(Long userId, AccountSubject subject) {
        FundAccount existing = selectUserAccount(userId, subject);
        return existing != null ? existing : insertUserAccount(userId, subject);
    }

    /**
     * 按需获取系统账户（全局唯一）。
     *
     * @param subject 系统科目（ACC_PLATFORM / ACC_TRANSIT / ACC_WITHDRAWN / ACC_ISSUED）
     * @return 账户实体
     */
    public FundAccount getOrCreateSystemAccount(AccountSubject subject) {
        FundAccount existing = fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.SYSTEM)
                .eq(FundAccount::getSubject, subject));
        if (existing != null) {
            return existing;
        }
        FundAccount account = new FundAccount();
        account.setOwnerType(AccountOwnerType.SYSTEM);
        account.setUserId(null);
        account.setSubject(subject);
        account.setBalance(0L);
        account.setVersion(0);
        try {
            fundAccountMapper.insert(account);
            return account;
        } catch (DuplicateKeyException e) {
            return fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                    .eq(FundAccount::getOwnerType, AccountOwnerType.SYSTEM)
                    .eq(FundAccount::getSubject, subject));
        }
    }

    /**
     * 查询用户某科目余额；账户不存在按 0 处理（读路径不建账户）。
     *
     * @param userId  用户 ID
     * @param subject 科目
     * @return 余额（分）
     */
    public long balanceOf(Long userId, AccountSubject subject) {
        FundAccount account = selectUserAccount(userId, subject);
        return account == null ? 0L : account.getBalance();
    }

    /**
     * 查询本人可用 / 冻结余额（FR-WALLET.QUERY.01）。
     *
     * @param userId 用户 ID
     * @return 两个科目余额
     */
    public FundBalanceView balances(Long userId) {
        return new FundBalanceView(
                balanceOf(userId, AccountSubject.ACC_AVAIL),
                balanceOf(userId, AccountSubject.ACC_FROZEN));
    }

    /**
     * 托管冻结：可用 → 冻结（FR-ESCROW.HOLD.01 / .02 / .04）。
     *
     * <p>先条件扣可用余额，受影响行数不为 1 即余额不足；再给冻结账户加等额。
     * 两步在同一事务内，任何一步失败都整体回滚。</p>
     *
     * @param userId 雇主用户 ID
     * @param amount 冻结金额（分）
     * @return 二元搬运（借方 ACC_AVAIL，贷方 ACC_FROZEN）
     * @throws ApiException 409 {@code INSUFFICIENT_BALANCE}
     */
    @Transactional
    public Transfer freeze(Long userId, long amount) {
        requirePositive(amount);
        int rows = fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.USER)
                .eq(FundAccount::getUserId, userId)
                .eq(FundAccount::getSubject, AccountSubject.ACC_AVAIL)
                .ge(FundAccount::getBalance, amount)
                .setSql("balance = balance - " + amount));
        if (rows != 1) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, INSUFFICIENT_MESSAGE);
        }

        FundAccount frozen = getOrCreateUserAccount(userId, AccountSubject.ACC_FROZEN);
        fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                .eq(FundAccount::getId, frozen.getId())
                .setSql("balance = balance + " + amount));

        FundAccount avail = selectUserAccount(userId, AccountSubject.ACC_AVAIL);
        return new Transfer(avail.getId(), AccountSubject.ACC_AVAIL,
                frozen.getId(), AccountSubject.ACC_FROZEN);
    }

    /**
     * 退款：冻结 → 可用（FR-ESCROW.REFUND.01）。
     *
     * @param userId 雇主用户 ID
     * @param amount 退款金额（分）
     * @return 二元搬运（借方 ACC_FROZEN，贷方 ACC_AVAIL）
     * @throws ApiException 409 {@code INVALID_STATE_TRANSITION} 冻结余额不足（说明账实已不一致）
     */
    @Transactional
    public Transfer refund(Long userId, long amount) {
        requirePositive(amount);
        int rows = fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.USER)
                .eq(FundAccount::getUserId, userId)
                .eq(FundAccount::getSubject, AccountSubject.ACC_FROZEN)
                .ge(FundAccount::getBalance, amount)
                .setSql("balance = balance - " + amount));
        if (rows != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, FROZEN_NOT_ENOUGH_MESSAGE);
        }

        FundAccount avail = getOrCreateUserAccount(userId, AccountSubject.ACC_AVAIL);
        fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                .eq(FundAccount::getId, avail.getId())
                .setSql("balance = balance + " + amount));

        FundAccount frozen = selectUserAccount(userId, AccountSubject.ACC_FROZEN);
        return new Transfer(frozen.getId(), AccountSubject.ACC_FROZEN,
                avail.getId(), AccountSubject.ACC_AVAIL);
    }

    /**
     * 模拟充值：发行总额 → 可用余额，并写一个凭证批次（FR-WALLET.RECHARGE.01~.02）。
     *
     * <p>只校验单笔 ≤ 50,000 元；V2 不做单日累计限额（用户裁决 6A）。
     * 必须写流水，否则 V6 对账的「发行总额 = Σ 用户余额」恒等式对不上。</p>
     *
     * @param userId 用户 ID
     * @param amount 充值金额（分）
     * @return 充值后的余额视图
     * @throws ApiException 400 {@code INVALID_PARAMETER} 金额非正；
     *                      409 {@code RECHARGE_LIMIT_EXCEEDED} 超过单笔限额
     */
    @Transactional
    public FundBalanceView recharge(Long userId, long amount) {
        if (userId == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
        }
        requirePositive(amount);
        if (amount > MAX_RECHARGE_AMOUNT) {
            throw new ApiException(ErrorCode.RECHARGE_LIMIT_EXCEEDED, RECHARGE_LIMIT_MESSAGE);
        }

        FundAccount avail = getOrCreateUserAccount(userId, AccountSubject.ACC_AVAIL);
        FundAccount issued = getOrCreateSystemAccount(AccountSubject.ACC_ISSUED);
        increase(avail.getId(), amount);
        increase(issued.getId(), amount);

        journalService.post(
                JournalBizType.RECHARGE,
                UUID.randomUUID().toString().replace("-", ""),
                JournalEventType.ACCOUNT_ISSUE,
                JournalTriggerType.USER,
                "模拟充值",
                List.of(
                        new JournalService.EntryDraft(issued.getId(), AccountSubject.ACC_ISSUED,
                                JournalDirection.DEBIT, amount),
                        new JournalService.EntryDraft(avail.getId(), AccountSubject.ACC_AVAIL,
                                JournalDirection.CREDIT, amount)));

        fundLedgerGuard.assertBalanced("RECHARGE", "userId=" + userId);
        return balances(userId);
    }

    /**
     * 验收释放：冻结 → 在途（FR-ESCROW.RELEASE.01）。
     *
     * @param employerId 雇主用户 ID
     * @param amount     释放金额（分），等于该名额的应结金额 S
     * @return 二元搬运（借方 ACC_FROZEN，贷方 ACC_TRANSIT）
     * @throws ApiException 409 冻结余额不足（账实不一致）
     */
    @Transactional
    public Transfer releaseToTransit(Long employerId, long amount) {
        requirePositive(amount);
        FundAccount frozen = deductUserBalance(employerId, AccountSubject.ACC_FROZEN, amount,
                FROZEN_NOT_ENOUGH_MESSAGE);
        FundAccount transit = getOrCreateSystemAccount(AccountSubject.ACC_TRANSIT);
        increase(transit.getId(), amount);
        return new Transfer(frozen.getId(), AccountSubject.ACC_FROZEN,
                transit.getId(), AccountSubject.ACC_TRANSIT);
    }

    /**
     * 零实付结算：冻结 → 平台收入（FR-SETTLE.FEE.03）。
     *
     * @param employerId 雇主用户 ID
     * @param amount     应结金额（分），全部为服务费
     * @return 二元搬运（借方 ACC_FROZEN，贷方 ACC_PLATFORM）
     * @throws ApiException 409 冻结余额不足（账实不一致）
     */
    @Transactional
    public Transfer settleZeroPay(Long employerId, long amount) {
        requirePositive(amount);
        FundAccount frozen = deductUserBalance(employerId, AccountSubject.ACC_FROZEN, amount,
                FROZEN_NOT_ENOUGH_MESSAGE);
        FundAccount platform = getOrCreateSystemAccount(AccountSubject.ACC_PLATFORM);
        increase(platform.getId(), amount);
        return new Transfer(frozen.getId(), AccountSubject.ACC_FROZEN,
                platform.getId(), AccountSubject.ACC_PLATFORM);
    }

    /**
     * 打款成功：在途资金结清（FR-PAY.CONFIRM.01）。
     *
     * <p>借方恒为在途账户的应结金额 S；贷方按实际出款 Y、平台服务费 F、
     * 雇主差额（S−Y−F）拆分，金额为 0 的腿不生成。调用方必须先保证三者相加等于 S，
     * 本方法再做一次 {@link Math#addExact} 校验，防止分录模板算错。</p>
     *
     * @param workerId        劳动者用户 ID
     * @param employerId      雇主用户 ID
     * @param transitAmount   在途扣减总额（分）
     * @param actualAmount    劳动者实付（分）
     * @param feeAmount       平台服务费（分），可为 0
     * @param employerRefund  退回雇主可用余额的差额（分），可为 0
     * @return 借贷双方账户与金额
     */
    @Transactional
    public PayoutSettlement settlePayout(Long workerId, Long employerId, long transitAmount,
                                         long actualAmount, long feeAmount, long employerRefund) {
        requirePositive(transitAmount);
        long creditsSum = Math.addExact(Math.addExact(actualAmount, feeAmount), employerRefund);
        if (creditsSum != transitAmount) {
            throw new IllegalStateException("打款结算不平：在途 " + transitAmount
                    + " ≠ 实付 " + actualAmount + " + 服务费 " + feeAmount + " + 差额 " + employerRefund);
        }
        if (actualAmount < 0 || feeAmount < 0 || employerRefund < 0) {
            throw new IllegalArgumentException("打款结算金额不能为负");
        }

        FundAccount transit = getOrCreateSystemAccount(AccountSubject.ACC_TRANSIT);
        int rows = fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                .eq(FundAccount::getId, transit.getId())
                .ge(FundAccount::getBalance, transitAmount)
                .setSql("balance = balance - " + transitAmount));
        if (rows != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, TRANSIT_NOT_ENOUGH_MESSAGE);
        }

        List<SettlementLeg> credits = new java.util.ArrayList<>(3);
        if (actualAmount > 0) {
            FundAccount worker = getOrCreateUserAccount(workerId, AccountSubject.ACC_AVAIL);
            increase(worker.getId(), actualAmount);
            credits.add(new SettlementLeg(worker.getId(), AccountSubject.ACC_AVAIL, actualAmount));
        }
        if (feeAmount > 0) {
            FundAccount platform = getOrCreateSystemAccount(AccountSubject.ACC_PLATFORM);
            increase(platform.getId(), feeAmount);
            credits.add(new SettlementLeg(platform.getId(), AccountSubject.ACC_PLATFORM, feeAmount));
        }
        if (employerRefund > 0) {
            FundAccount employer = getOrCreateUserAccount(employerId, AccountSubject.ACC_AVAIL);
            increase(employer.getId(), employerRefund);
            credits.add(new SettlementLeg(employer.getId(), AccountSubject.ACC_AVAIL, employerRefund));
        }
        return new PayoutSettlement(transit.getId(), transitAmount, List.copyOf(credits));
    }

    /**
     * 冲正的一笔借方。
     *
     * @param accountId 账户 ID
     * @param subject   科目
     * @param amount    金额（分）
     */
    public record ReverseLeg(Long accountId, AccountSubject subject, long amount) {
    }

    /**
     * 冲正结果：劳动者 / 平台 / 雇主三笔借方，在途一笔贷方。
     *
     * @param transitAccountId 在途账户 ID（贷方）
     * @param transitAmount    在途增加总额（分）
     * @param debits           借方明细
     */
    public record PayoutReverse(Long transitAccountId, long transitAmount, List<ReverseLeg> debits) {
    }

    /**
     * 对账冲正：把已结算出去的钱退回在途，等待重新出站（V6，FR-RECON.HANDLE.01）。
     *
     * <p>借方为打款成功时贷方的镜像：劳动者可用（实付）、平台收入（服务费）、
     * 雇主可用（雇主差额）。任何一腿余额不足都抛 {@code INVALID_STATE_TRANSITION}，
     * 由调用方把差异单保持「待处理」，绝不透支。</p>
     *
     * @param workerId       劳动者用户 ID
     * @param employerId     雇主用户 ID
     * @param workerAmount   劳动者需退回金额（分）
     * @param feeAmount      平台需退回服务费（分）
     * @param employerRefund 雇主需退回差额（分）
     * @return 借贷账户与金额
     */
    @Transactional
    public PayoutReverse reversePayout(Long workerId, Long employerId, long workerAmount,
                                       long feeAmount, long employerRefund) {
        if (workerAmount < 0 || feeAmount < 0 || employerRefund < 0) {
            throw new IllegalArgumentException("冲正金额不能为负");
        }
        long total = Math.addExact(Math.addExact(workerAmount, feeAmount), employerRefund);
        requirePositive(total);

        List<ReverseLeg> debits = new java.util.ArrayList<>(3);
        if (workerAmount > 0) {
            FundAccount worker = deductUserBalance(workerId, AccountSubject.ACC_AVAIL, workerAmount,
                    "劳动者可用余额不足，无法冲正");
            debits.add(new ReverseLeg(worker.getId(), AccountSubject.ACC_AVAIL, workerAmount));
        }
        if (feeAmount > 0) {
            FundAccount platform = getOrCreateSystemAccount(AccountSubject.ACC_PLATFORM);
            int rows = fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                    .eq(FundAccount::getId, platform.getId())
                    .ge(FundAccount::getBalance, feeAmount)
                    .setSql("balance = balance - " + feeAmount));
            if (rows != 1) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "平台服务费余额不足，无法冲正");
            }
            debits.add(new ReverseLeg(platform.getId(), AccountSubject.ACC_PLATFORM, feeAmount));
        }
        if (employerRefund > 0) {
            FundAccount employer = deductUserBalance(employerId, AccountSubject.ACC_AVAIL,
                    employerRefund, "雇主可用余额不足，无法冲正");
            debits.add(new ReverseLeg(employer.getId(), AccountSubject.ACC_AVAIL, employerRefund));
        }
        FundAccount transit = getOrCreateSystemAccount(AccountSubject.ACC_TRANSIT);
        increase(transit.getId(), total);
        return new PayoutReverse(transit.getId(), total, List.copyOf(debits));
    }

    /**
     * 对账差额调整：把本地与通道的差额在劳动者与雇主之间搬运（V6，FR-RECON.HANDLE.01）。
     *
     * <p>{@code delta > 0} 表示劳动者多记，借方为劳动者、贷方为雇主；
     * {@code delta < 0} 方向相反。差额为 0 抛参数错误。</p>
     *
     * @param workerId   劳动者用户 ID
     * @param employerId 雇主用户 ID
     * @param delta      劳动者应调减金额（分），负数为调增
     * @return 借贷双方账户
     */
    @Transactional
    public Transfer adjustDifference(Long workerId, Long employerId, long delta) {
        if (delta == 0) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "差额为 0，无需调整");
        }
        long amount = Math.abs(delta);
        if (delta > 0) {
            FundAccount worker = deductUserBalance(workerId, AccountSubject.ACC_AVAIL, amount,
                    "劳动者可用余额不足，无法调整差额");
            FundAccount employer = getOrCreateUserAccount(employerId, AccountSubject.ACC_AVAIL);
            increase(employer.getId(), amount);
            return new Transfer(worker.getId(), AccountSubject.ACC_AVAIL,
                    employer.getId(), AccountSubject.ACC_AVAIL);
        }
        FundAccount employer = deductUserBalance(employerId, AccountSubject.ACC_AVAIL, amount,
                "雇主可用余额不足，无法调整差额");
        FundAccount worker = getOrCreateUserAccount(workerId, AccountSubject.ACC_AVAIL);
        increase(worker.getId(), amount);
        return new Transfer(employer.getId(), AccountSubject.ACC_AVAIL,
                worker.getId(), AccountSubject.ACC_AVAIL);
    }

    private FundAccount deductUserBalance(Long userId, AccountSubject subject, long amount,
                                          String insufficientMessage) {
        FundAccount account = getOrCreateUserAccount(userId, subject);
        int rows = fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                .eq(FundAccount::getId, account.getId())
                .ge(FundAccount::getBalance, amount)
                .setSql("balance = balance - " + amount));
        if (rows != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, insufficientMessage);
        }
        return account;
    }

    private FundAccount selectUserAccount(Long userId, AccountSubject subject) {
        return fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.USER)
                .eq(FundAccount::getUserId, userId)
                .eq(FundAccount::getSubject, subject));
    }

    private FundAccount insertUserAccount(Long userId, AccountSubject subject) {
        FundAccount account = new FundAccount();
        account.setOwnerType(AccountOwnerType.USER);
        account.setUserId(userId);
        account.setSubject(subject);
        account.setBalance(0L);
        account.setVersion(0);
        try {
            fundAccountMapper.insert(account);
            return account;
        } catch (DuplicateKeyException e) {
            // 并发首次建账户：撞 uk_fund_account_user 后回查，禁止「先查后插」的判断式幂等
            return selectUserAccount(userId, subject);
        }
    }

    private void increase(Long accountId, long amount) {
        fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                .eq(FundAccount::getId, accountId)
                .setSql("balance = balance + " + amount));
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_AMOUNT_MESSAGE,
                    java.util.Map.of("fields", java.util.Map.of("amount", INVALID_AMOUNT_MESSAGE)));
        }
    }
}
