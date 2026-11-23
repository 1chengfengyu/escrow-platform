package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeMapper;
import com.escrow.platform.engagement.application.AcceptanceService;
import com.escrow.platform.engagement.application.AcceptanceTxService;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
import com.escrow.platform.engagement.domain.EngagementDeliverable;
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
import com.escrow.platform.fund.domain.FundEscrowOrder;
import com.escrow.platform.fund.domain.FundJournalBatch;
import com.escrow.platform.fund.domain.FundJournalEntry;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.application.PayoutTxService;
import com.escrow.platform.settlement.domain.ChannelResult;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 验收 → 结算 → 记账 → 打款全链路真库测试（V4，FR-ACCEPT.* / SETTLE.* / ESCROW.RELEASE.01 /
 * PAY.* / RECON.BALANCE.01）。
 *
 * <p>只在 {@code ESCROW_IT=true} 且 MySQL 的 {@code escrow_test} 可用时运行。
 * 必须打真库：{@code uk_settlement_slot}、{@code uk_payout_success}、条件更新的线性化点
 * 与六科目恒等式，假 Mapper 证明不了。</p>
 *
 * <p>资金链路用例先断言「不该发生的事」：金额、余额、批次唯一，
 * 再断言状态；避免只验证状态而放过重复打款。</p>
 *
 * @since V4
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V4AcceptanceSettlementIntegrationTest {

    private static final long OWNER_ID = 1001L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final long WORKER_ID = 2001L;
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker1@example.com", UserRole.WORKER, IdentityType.WORKER);

    /** 单价 1,000 分，默认费率 300（3%）→ 服务费 30，实付 970。 */
    private static final long UNIT_PRICE = 1_000L;

    @Autowired
    private TaskPublishService taskPublishService;
    @Autowired
    private HireService hireService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private ContractQueryService contractQueryService;
    @Autowired
    private AcceptanceService acceptanceService;
    @Autowired
    private AcceptanceTxService acceptanceTxService;
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
    @Autowired
    private DisputeMapper disputeMapper;

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
        disputeMapper.delete(null);
        deliverableMapper.delete(null);
        performanceRecordMapper.delete(null);
        contractMapper.delete(null);
        applicationMapper.delete(null);
        taskSlotMapper.delete(null);
        taskMapper.delete(null);
    }

    /** FR-ACCEPT.MANUAL.01 + FR-SETTLE.GEN.01 + FEE.01 + PAY.CONFIRM.01：全链路成功。 */
    @Test
    void acceptPassShouldSettlePayAndBalanceLedger() {
        Long taskId = publishTask(1);
        Long contractId = hireAndDeliver(taskId);

        AcceptanceTxService.AcceptanceOutcome outcome =
                acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);

        assertThat(outcome.payoutId()).isNotNull();

        Settlement settlement = settlementMapper.selectById(outcome.settlementId());
        assertThat(settlement.getNetAmount()).isEqualTo(970L);

        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_COMPLETED);
        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_COMPLETED);
        assertThat(escrowDetailOf(contractId).getStatus()).isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_RELEASED);

        assertThat(settlement.getFeeAmount()).isEqualTo(30L);
        assertThat(settlement.getNetAmount()).isEqualTo(970L);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);

        PayoutOrder payout = payoutOrderMapper.selectById(outcome.payoutId());
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(payout.getActualAmount()).isEqualTo(970L);
        assertThat(payout.getChannelResult()).isEqualTo(ChannelResult.SUCCESS);

        assertThat(balanceOfUser(OWNER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(9_000L);
        assertThat(balanceOfUser(OWNER_ID, AccountSubject.ACC_FROZEN)).isZero();
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(970L);
        assertThat(balanceOfSystem(AccountSubject.ACC_PLATFORM)).isEqualTo(30L);
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isZero();

        assertJournalBatchesBalanced(JournalBizType.ESCROW_RELEASE, JournalBizType.PAYOUT_SETTLE);
        assertLedgerIdentity();
    }

    /** FR-SETTLE.FEE.03：费率 100% → 实付 0，不经在途、不生成打款单。 */
    @Test
    void zeroPayShouldGoStraightToPlatformWithoutPayout() {
        Long taskId = publishTask(1);
        Long contractId = hireAndDeliver(taskId);
        forceFeeRateToFull(taskId);

        AcceptanceTxService.AcceptanceOutcome outcome =
                acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);

        assertThat(outcome.payoutId()).isNull();
        assertThat(payoutOrderMapper.selectCount(null)).isZero();

        Settlement settlement = settlementMapper.selectById(outcome.settlementId());
        assertThat(settlement.getNetAmount()).isZero();
        assertThat(settlement.getFeeAmount()).isEqualTo(UNIT_PRICE);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);

        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_COMPLETED);
        assertThat(escrowDetailOf(contractId).getStatus()).isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_RELEASED);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
        assertThat(balanceOfSystem(AccountSubject.ACC_PLATFORM)).isEqualTo(UNIT_PRICE);
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isZero();
        assertLedgerIdentity();
    }

    /** FR-ACCEPT.RESULT.02 + GUARD.01：拒收冻结资金、开争议，之后不能再验收。 */
    @Test
    void rejectShouldFreezeEscrowOpenDisputeAndBlockAccept() {
        Long taskId = publishTask(1);
        Long contractId = hireAndDeliver(taskId);

        AcceptanceTxService.AcceptanceOutcome outcome =
                acceptanceService.accept(OWNER, contractId, AcceptanceDecision.REJECT, "照片不清晰");

        assertThat(outcome.disputeId()).isNotNull();
        assertThat(outcome.settlementId()).isNull();
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_DELIVERED);
        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_PENDING_ARBITRATION);
        assertThat(escrowDetailOf(contractId).getStatus()).isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_FROZEN);

        Dispute dispute = disputeMapper.selectById(outcome.disputeId());
        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.DISPUTE_PENDING);
        assertThat(dispute.getReason()).isEqualTo("照片不清晰");
        assertThat(settlementMapper.selectCount(null)).isZero();
        assertThat(payoutOrderMapper.selectCount(null)).isZero();

        ApiException ex = catchThrowableOfType(
                () -> acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null),
                ApiException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DISPUTE_ACTIVE);
        assertThat(settlementMapper.selectCount(null)).isZero();
        assertLedgerIdentity();
    }

    /** FR-ACCEPT.GUARD.01：未交付不能验收，不留任何结算 / 打款痕迹。 */
    @Test
    void acceptShouldBeRejectedWhenNotDelivered() {
        Long taskId = publishTask(1);
        Long contractId = hire(taskId);
        contractService.start(WORKER, contractId);

        ApiException ex = catchThrowableOfType(
                () -> acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(settlementMapper.selectCount(null)).isZero();
        assertThat(payoutOrderMapper.selectCount(null)).isZero();
        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_IN_PROGRESS);
    }

    /** 重复验收：第二次 409，结算单与打款单各只有一份（uk_settlement_slot / uk_payout_success）。 */
    @Test
    void secondAcceptShouldNotCreateSecondSettlementOrPayout() {
        Long taskId = publishTask(1);
        Long contractId = hireAndDeliver(taskId);
        acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null);

        ApiException ex = catchThrowableOfType(
                () -> acceptanceService.accept(OWNER, contractId, AcceptanceDecision.PASS, null),
                ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(settlementMapper.selectCount(null)).isEqualTo(1L);
        assertThat(payoutOrderMapper.selectCount(null)).isEqualTo(1L);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(970L);
    }

    /** FR-ACCEPT.AUTO.01：48 小时自动验收按 SYSTEM 记账并完成打款。 */
    @Test
    void autoAcceptAfterThresholdShouldSettle() {
        Long taskId = publishTask(1);
        Long contractId = hireAndDeliver(taskId);
        contractMapper.update(null, new LambdaUpdateWrapper<EngagementContract>()
                .eq(EngagementContract::getId, contractId)
                .set(EngagementContract::getDeliveredAt, LocalDateTime.now().minusHours(49)));

        LocalDateTime cutoff = LocalDateTime.now().minusHours(48);
        assertThat(contractQueryService.findOverdueDeliveredContractIds(cutoff, 100))
                .contains(contractId);

        AcceptanceTxService.AcceptanceOutcome outcome = acceptanceService.autoAccept(contractId);

        assertThat(outcome).isNotNull();
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_COMPLETED);
        assertThat(onlySlotOf(taskId).getStatus()).isEqualTo(SlotStatus.SLOT_COMPLETED);
        assertThat(payoutOrderMapper.selectById(outcome.payoutId()).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(970L);
        assertJournalBatchesBalanced(JournalBizType.PAYOUT_SETTLE);
        assertLedgerIdentity();
    }

    /** FR-PAY.ISSUE.03：结果未知 → 待查证，原子单不再被扫描重发，结算仍停在打款中。 */
    @Test
    void unknownPayoutShouldEnterVerificationAndNeverBeResent() {
        Long taskId = publishTask(1);
        Long contractId = hireAndDeliver(taskId);

        // 只做事务内验收（不触发 T2 出站），手工模拟「已出站但结果未知」
        AcceptanceTxService.AcceptanceOutcome outcome =
                acceptanceTxService.decide(OWNER, contractId, AcceptanceDecision.PASS, null);
        Long payoutId = outcome.payoutId();
        assertThat(payoutTxService.claimForDispatch(payoutId)).isNotNull();

        payoutTxService.handleUnknown(payoutId, ChannelResult.TIMEOUT);

        assertThat(payoutOrderMapper.selectById(payoutId).getStatus())
                .isEqualTo(PayoutStatus.PAYOUT_PENDING_VERIFICATION);
        assertThat(settlementMapper.selectById(outcome.settlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_PAYING);
        assertThat(payoutTxService.claimForDispatch(payoutId))
                .as("待查证的单据不得再次出站")
                .isNull();
        assertThat(balanceOfSystem(AccountSubject.ACC_TRANSIT)).isEqualTo(UNIT_PRICE);
        assertThat(balanceOfUser(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
        assertLedgerIdentity();
    }

    // ==================== 场景搭建 ====================

    private Long publishTask(int slotCount) {
        fundAccountService.recharge(OWNER_ID, 10_000L);
        Task task = new Task();
        task.setEmployerId(OWNER_ID);
        task.setTitle("V4 验收结算测试任务-" + System.nanoTime());
        task.setDescription("验收 → 结算 → 打款全链路");
        task.setCategory("地推");
        task.setUnitPrice(UNIT_PRICE);
        task.setSlotCount(slotCount);
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

    private Long hire(Long taskId) {
        EngagementApplication application = new EngagementApplication();
        application.setId(IdWorker.getId());
        application.setTaskId(taskId);
        application.setWorkerId(WORKER_ID);
        application.setStatus(ApplicationStatus.APPLY_PENDING);
        applicationMapper.insert(application);
        return hireService.hire(OWNER, taskId, application.getId());
    }

    private Long hireAndDeliver(Long taskId) {
        Long contractId = hire(taskId);
        contractService.start(WORKER, contractId);
        contractService.deliver(WORKER, contractId,
                DeliverableSubmission.from(List.of("https://img.example.com/v4-1.jpg"), "已完成"));
        return contractId;
    }

    /** 把托管单费率快照改成 10000（100%），模拟「应结全额为服务费」的零实付场景。 */
    private void forceFeeRateToFull(Long taskId) {
        FundEscrowOrder order = fundEscrowOrderMapper.selectOne(
                new LambdaQueryWrapper<FundEscrowOrder>().eq(FundEscrowOrder::getTaskId, taskId));
        fundEscrowOrderMapper.update(null, new LambdaUpdateWrapper<FundEscrowOrder>()
                .eq(FundEscrowOrder::getId, order.getId())
                .set(FundEscrowOrder::getFeeRate, 10_000));
    }

    // ==================== 断言辅助 ====================

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

    private void assertJournalBatchesBalanced(JournalBizType... bizTypes) {
        for (JournalBizType bizType : bizTypes) {
            List<FundJournalBatch> batches = fundJournalBatchMapper.selectList(
                    new LambdaQueryWrapper<FundJournalBatch>().eq(FundJournalBatch::getBizType, bizType));
            assertThat(batches).as("凭证批次 %s 应存在", bizType).isNotEmpty();
            for (FundJournalBatch batch : batches) {
                List<FundJournalEntry> entries = fundJournalEntryMapper.selectList(
                        new LambdaQueryWrapper<FundJournalEntry>()
                                .eq(FundJournalEntry::getJournalBatchId, batch.getId()));
                long debit = entries.stream().filter(e -> e.getDirection() == JournalDirection.DEBIT)
                        .mapToLong(FundJournalEntry::getAmount).sum();
                long credit = entries.stream().filter(e -> e.getDirection() == JournalDirection.CREDIT)
                        .mapToLong(FundJournalEntry::getAmount).sum();
                assertThat(debit).as("批次借贷平衡 bizNo=%s", batch.getBizNo()).isEqualTo(credit);
            }
        }
    }

    /** ACC_ISSUED = Σ 用户余额 + Σ 系统余额。 */
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
