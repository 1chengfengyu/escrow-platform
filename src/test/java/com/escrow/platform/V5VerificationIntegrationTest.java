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
import com.escrow.platform.settlement.application.PayoutVerificationService;
import com.escrow.platform.settlement.domain.ChannelOutcome;
import com.escrow.platform.settlement.domain.ChannelResult;
import com.escrow.platform.settlement.domain.ChannelVerification;
import com.escrow.platform.settlement.domain.ChannelVerificationResult;
import com.escrow.platform.settlement.domain.PaymentChannel;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.PayoutVerificationRecord;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未知态查证的真库测试（V5，FR-PAY.UNKNOWN.01~.03）。
 *
 * <p>本类用可控通道替换模拟通道，才能在同一次运行里分别造出
 * 「通道其实成功」与「通道确认没这笔」两种查证返回。</p>
 *
 * <p>两条硬规则：查证成功必须能把钱补上账（不能因为回调丢了就永远挂着）；
 * 通道确认没这笔才能判失败重试，且资金不得提前离开在途。</p>
 *
 * @since V5
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
@Import(V5VerificationIntegrationTest.FakeChannelConfig.class)
class V5VerificationIntegrationTest {

    private static final long OWNER_ID = 1201L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final long WORKER_ID = 2201L;
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
    private PayoutVerificationService payoutVerificationService;
    @Autowired
    private com.escrow.platform.settlement.application.PayoutRetryService payoutRetryService;
    @Autowired
    private ControllableChannel channel;
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
        channel.reset();
    }

    /** 通道已出款但回调丢失：查证成功必须补账，资金从在途结清。 */
    @Test
    void verificationShouldRecoverLostCallback() {
        channel.nextOutcome = ChannelOutcome.of(ChannelResult.LOST_RESPONSE);
        var outcome = acceptanceService.accept(OWNER, hireAndDeliver(publishTask()),
                AcceptanceDecision.PASS, null);
        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAYOUT_PENDING_VERIFICATION);

        channel.nextVerification = ChannelVerificationResult.success(payout.getAmount());

        assertThat(payoutVerificationService.verify(payout.getId()))
                .isEqualTo(ChannelVerification.SUCCESS);

        assertThat(payoutOrderMapper.selectById(payout.getId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(settlementMapper.selectById(outcome.settlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(payout.getAmount());
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isZero();
        assertThat(balanceOfSystem(AccountSubject.ACC_PLATFORM)).isEqualTo(30L);
        assertLedgerIdentity();
    }

    /** 通道确认没这笔：判失败进入待重试，钱仍在在途，且留下查证流水。 */
    @Test
    void notFoundShouldFailAndWaitForRetry() {
        channel.nextOutcome = ChannelOutcome.of(ChannelResult.LOST_RESPONSE);
        var outcome = acceptanceService.accept(OWNER, hireAndDeliver(publishTask()),
                AcceptanceDecision.PASS, null);
        channel.nextVerification = ChannelVerificationResult.notFound();

        assertThat(payoutVerificationService.verify(outcome.payoutId()))
                .isEqualTo(ChannelVerification.NOT_FOUND);

        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_FAIL);
        assertThat(settlementMapper.selectById(outcome.settlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_PENDING_RETRY);
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isEqualTo(UNIT_PRICE);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();

        // v5-c3：待重试 → 新尝试（新请求号、序号 +1），结算回到打款中
        PayoutOrder failed = payoutOrderMapper.selectById(outcome.payoutId());
        Long retryPayoutId = payoutRetryService.startNextAttempt(outcome.settlementId());
        assertThat(retryPayoutId).isNotNull();
        PayoutOrder retry = payoutOrderMapper.selectById(retryPayoutId);
        assertThat(retry.getAttemptSeq()).isEqualTo(2);
        assertThat(retry.getRequestNo()).isNotEqualTo(failed.getRequestNo());
        assertThat(retry.getStatus()).isEqualTo(PayoutStatus.PAYOUT_PENDING_EXECUTE);
        assertThat(settlementMapper.selectById(outcome.settlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_PAYING);

        List<PayoutVerificationRecord> records = payoutVerificationMapper.selectList(
                new LambdaQueryWrapper<PayoutVerificationRecord>()
                        .eq(PayoutVerificationRecord::getPayoutId, outcome.payoutId()));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getResult()).isEqualTo(ChannelVerification.NOT_FOUND);
        assertLedgerIdentity();
    }

    /** 已经成功的单据不会被再次查证，也不会二次分账。 */
    @Test
    void successShouldNotBeVerifiedAgain() {
        channel.nextOutcome = ChannelOutcome.success(UNIT_PRICE - 30L);
        var outcome = acceptanceService.accept(OWNER, hireAndDeliver(publishTask()),
                AcceptanceDecision.PASS, null);
        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);

        long before = balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL);
        assertThat(payoutVerificationService.verify(outcome.payoutId())).isNull();
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(before);
    }

    private Long publishTask() {
        fundAccountService.recharge(OWNER_ID, "SETUP-" + System.nanoTime(), 10_000L);
        Task task = new Task();
        task.setEmployerId(OWNER_ID);
        task.setTitle("V5 查证测试任务-" + System.nanoTime());
        task.setDescription("未知态查证");
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
                DeliverableSubmission.from(List.of("https://img.example.com/v5-verify.jpg"), "已完成"));
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

    /**
     * 可控通道：只在本测试类里替换模拟实现，其余环境不受影响。
     */
    static class ControllableChannel implements PaymentChannel {

        volatile ChannelOutcome nextOutcome = ChannelOutcome.of(ChannelResult.LOST_RESPONSE);
        volatile ChannelVerificationResult nextVerification = ChannelVerificationResult.notFound();

        void reset() {
            nextOutcome = ChannelOutcome.of(ChannelResult.LOST_RESPONSE);
            nextVerification = ChannelVerificationResult.notFound();
        }

        @Override
        public ChannelOutcome pay(String requestNo, long amount) {
            return nextOutcome;
        }

        @Override
        public ChannelVerificationResult verify(String requestNo, long expectedAmount) {
            return nextVerification;
        }
    }

    /** 用 @Primary 覆盖生产环境的模拟通道。 */
    @TestConfiguration
    static class FakeChannelConfig {

        @Bean
        @Primary
        ControllableChannel controllableChannel() {
            return new ControllableChannel();
        }
    }
}
