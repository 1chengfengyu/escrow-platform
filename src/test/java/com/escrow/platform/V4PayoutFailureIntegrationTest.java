package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.engagement.application.AcceptanceService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementApplicationMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementContractMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementDeliverableMapper;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementPerformanceRecordMapper;
import com.escrow.platform.fund.application.FundAccountService;
import com.escrow.platform.fund.domain.AccountOwnerType;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.EscrowDetailStatus;
import com.escrow.platform.fund.domain.FundAccount;
import com.escrow.platform.fund.domain.FundEscrowDetail;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.application.PayoutTxService;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.Settlement;
import com.escrow.platform.settlement.domain.SettlementStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementMapper;
import com.escrow.platform.task.application.TaskPublishService;
import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;
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
 * 打款明确失败的真库测试（V4，FR-PAY.ISSUE.02 / CR-13）。
 *
 * <p>独立一个测试类只为了让模拟通道固定返回 FAIL：
 * {@code escrow.payout.gateway-mode} 是启动期 {@code @Value}，不能在用例里改。</p>
 *
 * <p>失败路径的硬规则：<b>钱只到在途，不到劳动者</b>；额度未用尽进「待重试」，
 * 额度用尽进「待人工」，两种情况都不允许再重复出款。</p>
 *
 * @since V4
 */
@SpringBootTest(properties = "escrow.payout.gateway-mode=FAIL")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V4PayoutFailureIntegrationTest {

    private static final long OWNER_ID = 1001L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final long WORKER_ID = 2001L;
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker1@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final long UNIT_PRICE = 1_000L;

    @Autowired
    private TaskPublishService taskPublishService;
    @Autowired
    private HireService hireService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private AcceptanceService acceptanceService;
    @Autowired
    private PayoutTxService payoutTxService;
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

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
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

    /** FR-PAY.ISSUE.02：明确失败 → 打款单失败、结算待重试，资金停在在途。 */
    @Test
    void payoutFailShouldKeepFundsInTransitAndScheduleRetry() {
        Long taskId = publishTask();
        Long contractId = hireAndDeliver(taskId);

        var outcome = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);

        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAYOUT_FAIL);
        assertThat(payout.getAttemptSeq()).isEqualTo(1);
        assertThat(settlementMapper.selectById(outcome.settlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_PENDING_RETRY);

        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_COMPLETED);
        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_PENDING_SETTLEMENT);
        assertThat(escrowDetailOf(contractId).getStatus()).isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_RELEASING);

        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isEqualTo(UNIT_PRICE);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
        assertThat(balanceOfSystem(AccountSubject.ACC_PLATFORM)).isZero();
        assertLedgerIdentity();
    }

    /** CR-13：第 3 次尝试仍失败 → 打款单待人工，不自动继续。 */
    @Test
    void thirdFailureShouldGoToManualReview() {
        Long taskId = publishTask();
        Long contractId = hireAndDeliver(taskId);
        var outcome = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);
        Long payoutId = outcome.payoutId();
        Long settlementId = outcome.settlementId();

        // 第 2 次：模拟调度重新出站（V5 才做自动重试，这里只验证额度判定）
        prepareRetry(payoutId, settlementId, 2);
        assertThat(payoutTxService.claimForDispatch(payoutId)).isNotNull();
        payoutTxService.handleFail(payoutId);
        assertThat(payoutOrderMapper.selectById(payoutId).getStatus()).isEqualTo(PayoutStatus.PAYOUT_FAIL);
        assertThat(settlementMapper.selectById(settlementId).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_PENDING_RETRY);

        // 第 3 次：额度用尽 → 待人工，结算保持打款中
        prepareRetry(payoutId, settlementId, 3);
        assertThat(payoutTxService.claimForDispatch(payoutId)).isNotNull();
        payoutTxService.handleFail(payoutId);

        assertThat(payoutOrderMapper.selectById(payoutId).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_MANUAL_REVIEW);
        assertThat(settlementMapper.selectById(settlementId).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_PAYING);
        assertThat(payoutTxService.claimForDispatch(payoutId))
                .as("待人工的打款单不得再次出站")
                .isNull();
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isEqualTo(UNIT_PRICE);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
    }

    private void prepareRetry(Long payoutId, Long settlementId, int attemptSeq) {
        payoutOrderMapper.update(null, new LambdaUpdateWrapper<PayoutOrder>()
                .eq(PayoutOrder::getId, payoutId)
                .set(PayoutOrder::getStatus, PayoutStatus.PAYOUT_PENDING_EXECUTE)
                .set(PayoutOrder::getAttemptSeq, attemptSeq)
                .set(PayoutOrder::getStatusEnteredAt, LocalDateTime.now()));
        settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, settlementId)
                .set(Settlement::getStatus, SettlementStatus.SETTLEMENT_PAYING));
    }

    private Long publishTask() {
        fundAccountService.recharge(OWNER_ID, 10_000L);
        Task task = new Task();
        task.setEmployerId(OWNER_ID);
        task.setTitle("V4 打款失败测试任务-" + System.nanoTime());
        task.setDescription("打款失败与待人工");
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
                DeliverableSubmission.from(List.of("https://img.example.com/v4-fail.jpg"), "已完成"));
        return contractId;
    }

    private TaskSlot onlySlotOf(Long taskId) {
        return taskSlotMapper.selectOne(new LambdaQueryWrapper<TaskSlot>()
                .eq(TaskSlot::getTaskId, taskId));
    }

    private FundEscrowDetail escrowDetailOf(Long contractId) {
        Long slotId = contractMapper.selectById(contractId).getSlotId();
        return fundEscrowDetailMapper.selectOne(new LambdaQueryWrapper<FundEscrowDetail>()
                .eq(FundEscrowDetail::getSlotId, slotId));
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
