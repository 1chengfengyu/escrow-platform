package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.error.ApiException;
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
import com.escrow.platform.fund.domain.FundJournalBatch;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.api.ReconDecisionRequest;
import com.escrow.platform.settlement.application.PayoutRuntimeConfig;
import com.escrow.platform.settlement.application.ReconHandlingService;
import com.escrow.platform.settlement.application.ReconService;
import com.escrow.platform.settlement.domain.ChannelTxn;
import com.escrow.platform.settlement.domain.DifferenceResolution;
import com.escrow.platform.settlement.domain.DifferenceStatus;
import com.escrow.platform.settlement.domain.DifferenceType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.Settlement;
import com.escrow.platform.settlement.domain.SettlementDifference;
import com.escrow.platform.settlement.domain.SettlementStatus;
import com.escrow.platform.settlement.infrastructure.channel.ChannelStatementSource;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.ReconBatchMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementDifferenceMapper;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对账处置真库测试（V6，FR-RECON.HANDLE.01）：补记 / 冲正 / 差额调整 / 余额不足回滚。
 *
 * <p>资金链路用例<b>先断言余额</b>再断言状态：状态对了但钱走错，一样是事故。
 * 通道流水由受控实现注入，三种模式覆盖正常、通道无记录、通道金额不足。</p>
 *
 * @since V6
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V6ReconHandlingIntegrationTest {

    private static final long OWNER_ID = 1301L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner-handle@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final long WORKER_ID = 2301L;
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker-handle@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    /** 单价 1,000 分，默认费率 300（3%）→ 服务费 30，实付 970。 */
    private static final long UNIT_PRICE = 1_000L;
    private static final long NET_AMOUNT = 970L;
    private static final long FEE = 30L;

    /** 通道流水模式：镜像 / 空流水 / 少付 70 分。 */
    private enum StatementMode { MIRROR, EMPTY, SHORT }

    private static volatile StatementMode mode = StatementMode.MIRROR;

    @Autowired
    private TaskPublishService taskPublishService;
    @Autowired
    private HireService hireService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private AcceptanceService acceptanceService;
    @Autowired
    private FundAccountService fundAccountService;
    @Autowired
    private ReconService reconService;
    @Autowired
    private ReconHandlingService reconHandlingService;
    @Autowired
    private PayoutRuntimeConfig payoutRuntimeConfig;
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
    private ReconBatchMapper reconBatchMapper;
    @Autowired
    private SettlementDifferenceMapper settlementDifferenceMapper;

    /** 受控通道流水：按静态模式生成，测试用例自己切换。 */
    @TestConfiguration
    static class StatementConfig {

        @Bean
        @Primary
        ChannelStatementSource controllableStatement(PayoutOrderMapper payoutOrderMapper) {
            return date -> {
                if (mode == StatementMode.EMPTY) {
                    return List.of();
                }
                List<PayoutOrder> attempts = payoutOrderMapper.selectList(
                        new LambdaQueryWrapper<PayoutOrder>()
                                .ge(PayoutOrder::getCreatedAt, date.atStartOfDay())
                                .lt(PayoutOrder::getCreatedAt, date.plusDays(1).atStartOfDay()));
                List<ChannelTxn> statement = new ArrayList<>();
                for (PayoutOrder attempt : attempts) {
                    if (attempt.getStatus() == PayoutStatus.PAYOUT_SUCCESS) {
                        long amount = mode == StatementMode.SHORT
                                ? attempt.getActualAmount() - 70L : attempt.getActualAmount();
                        statement.add(new ChannelTxn(attempt.getRequestNo(), amount, true));
                    } else if (attempt.getStatus() == PayoutStatus.PAYOUT_PENDING_VERIFICATION) {
                        statement.add(new ChannelTxn(attempt.getRequestNo(), attempt.getAmount(), true));
                    }
                }
                return statement;
            };
        }
    }

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        mode = StatementMode.MIRROR;
        payoutRuntimeConfig.apply(java.util.Map.of("gateway.mode", "SUCCESS"));
        settlementDifferenceMapper.delete(null);
        reconBatchMapper.delete(null);
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

    /** 通道缺失 → 冲正：钱回在途，打款失败、结算待重试、名额待结算。 */
    @Test
    void channelMissingReverseShouldMoveMoneyBackToTransit() {
        mode = StatementMode.EMPTY;
        Long contractId = hireAndDeliver(publishTask());
        var outcome = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);
        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(userBalance(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(NET_AMOUNT);

        reconService.runDaily(LocalDate.now());
        SettlementDifference difference = onlyDifference(DifferenceType.CHANNEL_MISSING);
        Settlement settlement = settlementMapper.selectById(payout.getSettlementId());

        reconHandlingService.handle(ADMIN, difference.getId(),
                new ReconDecisionRequest(DifferenceResolution.RECON_REVERSE, null));

        assertThat(userBalance(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
        assertThat(systemBalance(AccountSubject.ACC_PLATFORM)).isZero();
        assertThat(systemBalance(AccountSubject.ACC_TRANSIT)).isEqualTo(UNIT_PRICE);
        assertThat(userBalance(OWNER_ID, AccountSubject.ACC_AVAIL))
                .isEqualTo(10_000L - UNIT_PRICE);

        PayoutOrder reversed = payoutOrderMapper.selectById(payout.getId());
        assertThat(reversed.getStatus()).isEqualTo(PayoutStatus.PAYOUT_FAIL);
        assertThat(settlementMapper.selectById(settlement.getId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_PENDING_RETRY);
        TaskSlot slot = taskSlotMapper.selectById(settlement.getSlotId());
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.SLOT_PENDING_SETTLEMENT);

        SettlementDifference handled = settlementDifferenceMapper.selectById(difference.getId());
        assertThat(handled.getStatus()).isEqualTo(DifferenceStatus.SETTLEMENT_DIFF_HANDLED);
        assertThat(handled.getResolution()).isEqualTo(DifferenceResolution.RECON_REVERSE);
        assertThat(handled.getResolverId()).isEqualTo(ADMIN.userId());
        assertThat(handled.getResolvedAt()).isNotNull();
        assertThat(fundJournalBatchMapper.selectCount(new LambdaQueryWrapper<FundJournalBatch>()
                .eq(FundJournalBatch::getBizType, JournalBizType.RECON_ADJUST)))
                .isEqualTo(1L);
    }

    /** 余额不足 → 冲正失败，差异单保持待处理，钱与状态都不动。 */
    @Test
    void reverseWithInsufficientBalanceShouldKeepDifferencePending() {
        mode = StatementMode.EMPTY;
        Long contractId = hireAndDeliver(publishTask());
        var outcome = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);
        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        reconService.runDaily(LocalDate.now());
        SettlementDifference difference = onlyDifference(DifferenceType.CHANNEL_MISSING);

        FundAccount worker = fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.USER)
                .eq(FundAccount::getUserId, WORKER_ID)
                .eq(FundAccount::getSubject, AccountSubject.ACC_AVAIL));
        fundAccountMapper.update(null, new LambdaUpdateWrapper<FundAccount>()
                .eq(FundAccount::getId, worker.getId())
                .setSql("balance = 0"));

        assertThatThrownBy(() -> reconHandlingService.handle(ADMIN, difference.getId(),
                new ReconDecisionRequest(DifferenceResolution.RECON_REVERSE, null)))
                .isInstanceOf(ApiException.class);

        assertThat(settlementDifferenceMapper.selectById(difference.getId()).getStatus())
                .isEqualTo(DifferenceStatus.SETTLEMENT_DIFF_PENDING);
        assertThat(payoutOrderMapper.selectById(payout.getId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(userBalance(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
    }

    /** 应答丢失 → 补记：按通道金额完成分账，差异单已处理。 */
    @Test
    void lostResponseBookShouldSettle() {
        // 运行期把通道切成超时，制造一笔「本地未知」；验收后再切回成功，避免影响其它用例
        payoutRuntimeConfig.apply(java.util.Map.of("gateway.mode", "TIMEOUT"));
        Long contractId = hireAndDeliver(publishTask());
        var outcome = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);
        payoutRuntimeConfig.apply(java.util.Map.of("gateway.mode", "SUCCESS"));
        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAYOUT_PENDING_VERIFICATION);

        reconService.runDaily(LocalDate.now());
        SettlementDifference difference = onlyDifference(DifferenceType.LOST_RESPONSE);

        reconHandlingService.handle(ADMIN, difference.getId(),
                new ReconDecisionRequest(DifferenceResolution.RECON_BOOK, null));

        assertThat(payoutOrderMapper.selectById(payout.getId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        Settlement settlement = settlementMapper.selectById(payout.getSettlementId());
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);
        assertThat(taskSlotMapper.selectById(settlement.getSlotId()).getStatus())
                .isEqualTo(SlotStatus.SLOT_COMPLETED);
        assertThat(userBalance(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(NET_AMOUNT);
        assertThat(systemBalance(AccountSubject.ACC_PLATFORM)).isEqualTo(FEE);
        assertThat(systemBalance(AccountSubject.ACC_TRANSIT)).isZero();
        assertThat(settlementDifferenceMapper.selectById(difference.getId()).getStatus())
                .isEqualTo(DifferenceStatus.SETTLEMENT_DIFF_HANDLED);
    }

    /** 金额不一致 → 差额调整：多记的 70 分从劳动者退回雇主，打款与结算状态不变。 */
    @Test
    void amountMismatchAdjustShouldMoveDeltaToEmployer() {
        mode = StatementMode.SHORT;
        Long contractId = hireAndDeliver(publishTask());
        var outcome = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);
        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        reconService.runDaily(LocalDate.now());
        SettlementDifference difference = onlyDifference(DifferenceType.AMOUNT_MISMATCH);
        assertThat(difference.getLocalAmount()).isEqualTo(NET_AMOUNT);
        assertThat(difference.getChannelAmount()).isEqualTo(NET_AMOUNT - 70L);

        reconHandlingService.handle(ADMIN, difference.getId(),
                new ReconDecisionRequest(DifferenceResolution.RECON_ADJUST, null));

        assertThat(userBalance(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(NET_AMOUNT - 70L);
        assertThat(userBalance(OWNER_ID, AccountSubject.ACC_AVAIL))
                .isEqualTo(10_000L - UNIT_PRICE + 70L);
        assertThat(systemBalance(AccountSubject.ACC_PLATFORM)).isEqualTo(FEE);
        assertThat(systemBalance(AccountSubject.ACC_TRANSIT)).isZero();
        assertThat(payoutOrderMapper.selectById(payout.getId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(settlementMapper.selectById(payout.getSettlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);
        assertThat(settlementDifferenceMapper.selectById(difference.getId()).getStatus())
                .isEqualTo(DifferenceStatus.SETTLEMENT_DIFF_HANDLED);
    }

    private SettlementDifference onlyDifference(DifferenceType type) {
        List<SettlementDifference> all = settlementDifferenceMapper.selectList(null);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getDiffType()).isEqualTo(type);
        return all.get(0);
    }

    private Long publishTask() {
        fundAccountService.recharge(OWNER_ID, 10_000L);
        Task task = new Task();
        task.setEmployerId(OWNER_ID);
        task.setTitle("V6 处置测试任务-" + System.nanoTime());
        task.setDescription("对账差异处置");
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
                DeliverableSubmission.from(List.of("https://img.example.com/v6-handle.jpg"), "已完成"));
        return contractId;
    }

    private long userBalance(Long userId, AccountSubject subject) {
        FundAccount account = fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.USER)
                .eq(FundAccount::getUserId, userId)
                .eq(FundAccount::getSubject, subject));
        return account == null ? 0L : account.getBalance();
    }

    private long systemBalance(AccountSubject subject) {
        FundAccount account = fundAccountMapper.selectOne(new LambdaQueryWrapper<FundAccount>()
                .eq(FundAccount::getOwnerType, AccountOwnerType.SYSTEM)
                .eq(FundAccount::getSubject, subject));
        return account == null ? 0L : account.getBalance();
    }
}
