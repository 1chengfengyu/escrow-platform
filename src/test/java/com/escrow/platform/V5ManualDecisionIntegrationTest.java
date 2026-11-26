package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.engagement.application.AcceptanceService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementDeliverableMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementPerformanceRecordMapper;
import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.fund.domain.AccountOwnerType;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.FundAccount;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.api.PayoutDecisionRequest;
import com.escrow.platform.settlement.application.PayoutAdminService;
import com.escrow.platform.settlement.domain.PayoutManualDecision;
import com.escrow.platform.settlement.domain.PayoutManualDecisionType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.SettlementStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutManualDecisionMapper;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutVerificationMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementMapper;
import com.escrow.platform.task.application.TaskPublishService;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.infrastructure.mapper.TaskMapper;
import com.escrow.platform.task.infrastructure.mapper.TaskSlotMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人工处置三选一的真库测试（V5，FR-PAY.MANUAL.01~.03）。
 *
 * <p>用 AMOUNT_MISMATCH 模式造出「已出款但金额不同」的待人工单，再分别验证
 * 零扣款与按实际金额两种处置的资金结果；转差异只落记录、资金不动。</p>
 *
 * @since V5
 */
@SpringBootTest(properties = "escrow.payout.gateway-mode=AMOUNT_MISMATCH")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V5ManualDecisionIntegrationTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final long OWNER_ID = 1301L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final long WORKER_ID = 2301L;
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker1@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final long UNIT_PRICE = 1_000L;
    private static final long FEE = 30L;

    @Autowired
    private TaskPublishService taskPublishService;
    @Autowired
    private HireService hireService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private AcceptanceService acceptanceService;
    @Autowired
    private PayoutAdminService payoutAdminService;
    @Autowired
    private FundAccountService fundAccountService;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskSlotMapper taskSlotMapper;
    @Autowired
    private EngagementApplicationMapper applicationMapper;
    @Autowired
    private EngagementContractMapper contractMapper;
    @Autowired
    private EngagementDeliverableMapper deliverableMapper;
    @Autowired
    private EngagementPerformanceRecordMapper performanceRecordMapper;
    @Autowired
    private FundAccountMapper fundAccountMapper;
    @Autowired
    private FundEscrowOrderMapper fundEscrowOrderMapper;
    @Autowired
    private FundEscrowDetailMapper fundEscrowDetailMapper;
    @Autowired
    private FundJournalBatchMapper fundJournalBatchMapper;
    @Autowired
    private FundJournalEntryMapper fundJournalEntryMapper;
    @Autowired
    private SettlementMapper settlementMapper;
    @Autowired
    private PayoutOrderMapper payoutOrderMapper;
    @Autowired
    private PayoutVerificationMapper payoutVerificationMapper;
    @Autowired
    private PayoutManualDecisionMapper payoutManualDecisionMapper;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        payoutManualDecisionMapper.delete(null);
        payoutVerificationMapper.delete(null);
        fundJournalEntryMapper.delete(null);
        fundJournalBatchMapper.delete(null);
        fundEscrowDetailMapper.delete(null);
        fundEscrowOrderMapper.delete(null);
        fundAccountMapper.delete(null);
        payoutOrderMapper.delete(null);
        settlementMapper.delete(null);
        deliverableMapper.delete(null);
        performanceRecordMapper.delete(null);
        contractMapper.delete(null);
        applicationMapper.delete(null);
        taskSlotMapper.delete(null);
        taskMapper.delete(null);
    }

    /** 零扣款：劳动者 0，平台服务费照收，其余退回雇主；总账仍平。 */
    @Test
    void zeroPaymentShouldRefundEmployerAndChargeFeeOnly() {
        var outcome = acceptanceService.accept(OWNER, hireAndDeliver(publishTask()),
                AcceptanceDecision.PASS, null);
        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_MANUAL_REVIEW);

        payoutAdminService.decide(ADMIN, outcome.payoutId(),
                new PayoutDecisionRequest(PayoutManualDecisionType.ZERO_PAYMENT, null, "凭据 ZP-001"));

        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getActualAmount()).isZero();
        assertThat(settlementMapper.selectById(outcome.settlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
        assertThat(balanceOfSystem(AccountSubject.ACC_PLATFORM)).isEqualTo(FEE);
        assertThat(balanceOfUser(OWNER_ID, AccountSubject.ACC_AVAIL))
                .isEqualTo(10_000L - UNIT_PRICE + (UNIT_PRICE - FEE));
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isZero();
        assertLedgerIdentity();

        List<PayoutManualDecision> decisions = payoutManualDecisionMapper.selectList(
                new LambdaQueryWrapper<PayoutManualDecision>()
                        .eq(PayoutManualDecision::getPayoutId, outcome.payoutId()));
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecision()).isEqualTo(PayoutManualDecisionType.ZERO_PAYMENT);
    }

    /** 按实际金额：劳动者拿通道实际金额，差额退回雇主。 */
    @Test
    void confirmActualShouldUseChannelAmountAndRefundDifference() {
        var outcome = acceptanceService.accept(OWNER, hireAndDeliver(publishTask()),
                AcceptanceDecision.PASS, null);
        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        long actual = payout.getAmount() - 1;

        payoutAdminService.decide(ADMIN, outcome.payoutId(),
                new PayoutDecisionRequest(PayoutManualDecisionType.CONFIRM_ACTUAL, actual, "以对账单为准"));

        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getActualAmount()).isEqualTo(actual);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(actual);
        assertThat(balanceOfSystem(AccountSubject.ACC_PLATFORM)).isEqualTo(FEE);
        assertThat(balanceOfUser(OWNER_ID, AccountSubject.ACC_AVAIL))
                .isEqualTo(10_000L - UNIT_PRICE + (UNIT_PRICE - actual - FEE));
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isZero();
        assertLedgerIdentity();
    }

    /** 转差异：只留处置记录，单据与资金都不动。 */
    @Test
    void toDifferenceShouldKeepFundsInTransit() {
        var outcome = acceptanceService.accept(OWNER, hireAndDeliver(publishTask()),
                AcceptanceDecision.PASS, null);

        payoutAdminService.decide(ADMIN, outcome.payoutId(),
                new PayoutDecisionRequest(PayoutManualDecisionType.TO_DIFFERENCE, null, "待日终对账"));

        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_MANUAL_REVIEW);
        assertThat(settlementMapper.selectById(outcome.settlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_PAYING);
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isEqualTo(UNIT_PRICE);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
        assertLedgerIdentity();
    }

    private Long publishTask() {
        fundAccountService.recharge(OWNER_ID, "SETUP-" + System.nanoTime(), 10_000L);
        Task task = new Task();
        task.setEmployerId(OWNER_ID);
        task.setTitle("V5 人工处置测试任务-" + System.nanoTime());
        task.setDescription("三选一处置");
        task.setCategory("地推");
        task.setUnitPrice(UNIT_PRICE);
        task.setSlotCount(1);
        task.setStartTime(LocalDateTime.now().plusHours(1));
        task.setEndTime(LocalDateTime.now().plusDays(2));
        task.setWorkLocation("北京市朝阳区");
        task.setPublishedAt(null);
        task.setCancelledAt(null);
        task.setRecruitingClosed(false);
        taskMapper.insert(task);
        taskPublishService.publish(OWNER, task.getId());
        return task.getId();
    }

    private Long hireAndDeliver(Long taskId) {
        EngagementApplication application = new EngagementApplication();
        application.setId(IdWorker.getId());
        application.setTaskId(taskId);
        application.setWorkerId(WORKER_ID);
        application.setStatus(ApplicationStatus.APPLY_PENDING);
        applicationMapper.insert(application);
        Long contractId = hireService.hire(OWNER, taskId, application.getId());
        contractService.start(WORKER, contractId);
        contractService.deliver(WORKER, contractId,
                DeliverableSubmission.from(List.of("https://img.example.com/v5-manual.jpg"), "已完成"));
        return contractId;
    }

    private long balanceOfUser(Long userId, AccountSubject subject) {
        FundAccount account = fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.USER)
                .eq(FundAccount::getUserId, userId)
                .eq(FundAccount::getSubject, subject));
        return account == null ? 0L : account.getBalance();
    }

    private long balanceOfSystem(AccountSubject subject) {
        FundAccount account = fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.SYSTEM)
                .eq(FundAccount::getSubject, subject));
        return account == null ? 0L : account.getBalance();
    }

    private void assertLedgerIdentity() {
        List<FundAccount> accounts = fundAccountMapper.selectList(null);
        long issued = 0L;
        long rest = 0L;
        for (FundAccount account : accounts) {
            if (account.getSubject() == AccountSubject.ACC_ISSUED) {
                issued += account.getBalance();
            } else {
                rest += account.getBalance();
            }
        }
        assertThat(issued).as("六科目恒等式").isEqualTo(rest);
    }
}
