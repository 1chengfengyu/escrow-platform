package com.escrow.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.common.chat.domain.ChatConversation;
import com.escrow.platform.common.chat.domain.ChatMessage;
import com.escrow.platform.common.chat.domain.ChatMessageType;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatConversationMapper;
import com.escrow.platform.common.chat.infrastructure.mapper.ChatMessageMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.api.ChatEvidenceRequest;
import com.escrow.platform.dispute.api.DisputeEvidenceRequest;
import com.escrow.platform.dispute.api.DisputeEvidenceView;
import com.escrow.platform.dispute.application.ChatEvidenceService;
import com.escrow.platform.dispute.application.DisputeResolutionService;
import com.escrow.platform.dispute.application.DisputeService;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeEvidence;
import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeEvidenceMapper;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeMapper;
import com.escrow.platform.engagement.application.AcceptanceService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.engagement.application.HireService;
import com.escrow.platform.engagement.domain.AcceptanceDecision;
import com.escrow.platform.engagement.domain.ApplicationStatus;
import com.escrow.platform.engagement.domain.ContractStatus;
import com.escrow.platform.engagement.domain.DeliverableSubmission;
import com.escrow.platform.engagement.domain.EngagementApplication;
import com.escrow.platform.engagement.domain.EngagementContract;
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
import com.escrow.platform.settlement.application.PayoutRuntimeConfig;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 争议全链路真库测试（V6，FR-DISPUTE.OPEN.01 / EVIDENCE.01 / WITHDRAW.01 / CLOSE.01）。
 *
 * <p>资金用例先断言余额再断言状态：冻结、放款、退款三条路径的钱必须与状态同时正确。
 * 放款路径复用验收结算，因此除争议单外同时验证结算单、打款单、托管明细与签约。</p>
 *
 * @since V6
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V6DisputeIntegrationTest {

    private static final long OWNER_ID = 1401L;
    private static final LoginUser OWNER =
            new LoginUser(OWNER_ID, "owner-dispute@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final long WORKER_ID = 2401L;
    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker-dispute@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final long STRANGER_ID = 2402L;
    private static final LoginUser STRANGER =
            new LoginUser(STRANGER_ID, "stranger@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    /** 单价 1,000 分，费率 300（3%）→ 服务费 30，实付 970。 */
    private static final long UNIT_PRICE = 1_000L;
    private static final long NET_AMOUNT = 970L;
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
    private FundAccountService fundAccountService;
    @Autowired
    private DisputeService disputeService;
    @Autowired
    private DisputeResolutionService disputeResolutionService;
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
    private DisputeMapper disputeMapper;
    @Autowired
    private DisputeEvidenceMapper disputeEvidenceMapper;
    @Autowired
    private ChatEvidenceService chatEvidenceService;
    @Autowired
    private ChatConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        payoutRuntimeConfig.apply(java.util.Map.of("gateway.mode", "SUCCESS"));
        chatMessageMapper.delete(null);
        conversationMapper.delete(null);
        new JdbcTemplate(dataSource).update("DELETE FROM audit_log WHERE biz_type = 'DISPUTE'");
        disputeEvidenceMapper.delete(null);
        disputeMapper.delete(null);
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

    /** FR-DISPUTE.OPEN.01 / EVIDENCE.01：申诉冻结资金，双方证据互相可见，重复申诉与越权被拒。 */
    @Test
    void workerAppealShouldFreezeEscrowAndShareEvidence() {
        Long contractId = hireAndDeliver(publishTask());
        Long slotId = contractMapper.selectById(contractId).getSlotId();

        Long disputeId = disputeResolutionService.appeal(WORKER, contractId, "已按约定交付，请裁决");

        assertThat(disputeMapper.selectById(disputeId).getStatus()).isEqualTo(DisputeStatus.DISPUTE_PENDING);
        assertThat(disputeMapper.selectById(disputeId).getInitiatorType())
                .isEqualTo(DisputeInitiatorType.WORKER);
        assertThat(taskSlotMapper.selectById(slotId).getStatus())
                .isEqualTo(SlotStatus.SLOT_PENDING_ARBITRATION);
        assertThat(escrowDetail(slotId).getStatus()).isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_FROZEN);
        // 托管期间钱在雇主的冻结科目，不在系统在途科目（打款出站时才进在途）
        assertThat(userBalance(OWNER_ID, AccountSubject.ACC_FROZEN)).isEqualTo(UNIT_PRICE);

        disputeService.addEvidence(WORKER, disputeId, new DisputeEvidenceRequest("交付截图如下",
                List.of("https://img.example.com/worker-1.jpg")));
        disputeService.addEvidence(OWNER, disputeId, new DisputeEvidenceRequest("未按要求排版", null));

        assertThat(disputeService.detail(OWNER, disputeId).evidence()).hasSize(2);
        assertThat(disputeService.detail(WORKER, disputeId).evidence().get(0).submitterType())
                .isEqualTo(DisputeInitiatorType.WORKER);

        assertThatThrownBy(() -> disputeResolutionService.appeal(WORKER, contractId, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> disputeService.detail(STRANGER, disputeId))
                .isInstanceOf(ApiException.class);
    }

    /** FR-DISPUTE.WITHDRAW.01 / WITHDRAW.02：撤回还原资金与名额，再次申诉复用同一张单。 */
    @Test
    void withdrawShouldRestoreDeliveredAndReuseDispute() {
        Long contractId = hireAndDeliver(publishTask());
        Long slotId = contractMapper.selectById(contractId).getSlotId();
        Long disputeId = disputeResolutionService.appeal(WORKER, contractId, "申诉");

        disputeResolutionService.withdraw(WORKER, disputeId);

        Dispute withdrawn = disputeMapper.selectById(disputeId);
        assertThat(withdrawn.getStatus()).isEqualTo(DisputeStatus.DISPUTE_WITHDRAWN);
        assertThat(withdrawn.getWithdrawCount()).isEqualTo(1);
        assertThat(taskSlotMapper.selectById(slotId).getStatus()).isEqualTo(SlotStatus.SLOT_DELIVERED);
        assertThat(escrowDetail(slotId).getStatus()).isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_HELD);
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_DELIVERED);

        Long reopened = disputeResolutionService.appeal(WORKER, contractId, "再次申诉");

        assertThat(reopened).isEqualTo(disputeId);
        assertThat(disputeMapper.selectById(disputeId).getStatus()).isEqualTo(DisputeStatus.DISPUTE_PENDING);
        assertThat(disputeMapper.selectById(disputeId).getWithdrawCount()).isEqualTo(1);
        assertThat(taskSlotMapper.selectById(slotId).getStatus())
                .isEqualTo(SlotStatus.SLOT_PENDING_ARBITRATION);

        // 发起方已切换为劳动者，雇主不能代为撤回
        assertThatThrownBy(() -> disputeResolutionService.withdraw(OWNER, disputeId))
                .isInstanceOf(ApiException.class);
    }

    /** FR-DISPUTE.CLOSE.01（放款）：裁决即代验收，结算打款与正常验收完全一致。 */
    @Test
    void resolveReleaseShouldSettleAndPayout() {
        Long contractId = hireAndDeliver(publishTask());
        Long slotId = contractMapper.selectById(contractId).getSlotId();
        var rejected = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.REJECT, "排版不合格");
        Long disputeId = rejected.disputeId();
        assertThat(disputeId).isNotNull();

        disputeResolutionService.resolve(ADMIN, disputeId, DisputeResolution.RELEASE, "证据充分，放款");

        Dispute resolved = disputeMapper.selectById(disputeId);
        assertThat(resolved.getStatus()).isEqualTo(DisputeStatus.DISPUTE_RESOLVED);
        assertThat(resolved.getResolution()).isEqualTo(DisputeResolution.RELEASE);
        assertThat(resolved.getResolutionBasis()).isEqualTo("证据充分，放款");
        assertThat(resolved.getResolverId()).isEqualTo(ADMIN.userId());
        assertThat(resolved.getResolvedAt()).isNotNull();

        assertThat(userBalance(WORKER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(NET_AMOUNT);
        assertThat(systemBalance(AccountSubject.ACC_PLATFORM)).isEqualTo(FEE);
        assertThat(systemBalance(AccountSubject.ACC_TRANSIT)).isZero();
        assertThat(userBalance(OWNER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(10_000L - UNIT_PRICE);

        Settlement settlement = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getSlotId, slotId));
        PayoutOrder payout = payoutOrderMapper.selectOne(new LambdaQueryWrapper<PayoutOrder>()
                .eq(PayoutOrder::getSettlementId, settlement.getId()));
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAYOUT_SUCCESS);
        assertThat(settlementMapper.selectById(payout.getSettlementId()).getStatus())
                .isEqualTo(SettlementStatus.SETTLEMENT_COMPLETED);
        assertThat(taskSlotMapper.selectById(slotId).getStatus()).isEqualTo(SlotStatus.SLOT_COMPLETED);
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_COMPLETED);
        assertThat(escrowDetail(slotId).getStatus()).isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_RELEASED);
    }

    /** FR-DISPUTE.CLOSE.01（退款）：托管金额全额退回雇主，签约取消、名额已退款。 */
    @Test
    void resolveRefundShouldReturnEscrowToEmployer() {
        Long contractId = hireAndDeliver(publishTask());
        Long slotId = contractMapper.selectById(contractId).getSlotId();
        var rejected = acceptanceService.accept(OWNER, contractId, AcceptanceDecision.REJECT, "未按约定交付");
        Long disputeId = rejected.disputeId();

        disputeResolutionService.resolve(ADMIN, disputeId, DisputeResolution.REFUND, "未按约定交付");

        assertThat(userBalance(OWNER_ID, AccountSubject.ACC_AVAIL)).isEqualTo(10_000L);
        assertThat(userBalance(WORKER_ID, AccountSubject.ACC_AVAIL)).isZero();
        assertThat(systemBalance(AccountSubject.ACC_PLATFORM)).isZero();
        assertThat(systemBalance(AccountSubject.ACC_TRANSIT)).isZero();

        assertThat(disputeMapper.selectById(disputeId).getResolution()).isEqualTo(DisputeResolution.REFUND);
        assertThat(escrowDetail(slotId).getStatus()).isEqualTo(EscrowDetailStatus.ESCROW_DETAIL_REFUNDED);
        assertThat(taskSlotMapper.selectById(slotId).getStatus()).isEqualTo(SlotStatus.SLOT_REFUNDED);
        assertThat(contractMapper.selectById(contractId).getStatus())
                .isEqualTo(ContractStatus.CONTRACT_CANCELLED);
        assertThat(fundJournalBatchMapper.selectCount(new LambdaQueryWrapper<FundJournalBatch>()
                .eq(FundJournalBatch::getBizType, JournalBizType.ESCROW_REFUND))).isEqualTo(1L);

        // 已裁决后不能再补证据
        assertThatThrownBy(() -> disputeService.addEvidence(WORKER, disputeId,
                new DisputeEvidenceRequest("补充说明", null)))
                .isInstanceOf(ApiException.class);
    }

    /** 裁决权限与状态：非管理员 403、依据为空 400、重复裁决 409。 */
    @Test
    void resolveShouldEnforceAdminAndPendingState() {
        Long contractId = hireAndDeliver(publishTask());
        Long disputeId = disputeResolutionService.appeal(WORKER, contractId, "申诉");

        assertThatThrownBy(() -> disputeResolutionService.resolve(WORKER, disputeId,
                DisputeResolution.RELEASE, "放款")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> disputeResolutionService.resolve(ADMIN, disputeId,
                DisputeResolution.RELEASE, " ")).isInstanceOf(ApiException.class);

        disputeResolutionService.resolve(ADMIN, disputeId, DisputeResolution.RELEASE, "首次裁决");

        assertThatThrownBy(() -> disputeResolutionService.resolve(ADMIN, disputeId,
                DisputeResolution.REFUND, "重复裁决")).isInstanceOf(ApiException.class);
    }

    /**
     * 补遗 §5.4（V11）：真实争议单把会话消息<b>复制</b>为 {@code dispute_evidence}。
     *
     * <p>聊天表只读：取证后原消息必须仍在，且非当事人不能为争议取证。</p>
     */
    @Test
    void chatEvidenceShouldCopyConversationMessagesIntoDisputeEvidence() {
        Long taskId = publishTask();
        Long contractId = hireAndDeliver(taskId);
        Long disputeId = acceptanceService.accept(OWNER, contractId,
                AcceptanceDecision.REJECT, "排版不合格").disputeId();
        assertThat(disputeId).isNotNull();

        // 录用后 ChatEventDispatcher 已自动建好 CONTRACT 会话，取证直接复用
        ChatConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getTaskId, taskId)
                        .eq(ChatConversation::getEmployerId, OWNER_ID)
                        .eq(ChatConversation::getWorkerId, WORKER_ID));
        assertThat(conversation).isNotNull();

        ChatMessage message = new ChatMessage();
        message.setConversationId(conversation.getId());
        message.setSenderId(WORKER_ID);
        message.setMsgType(ChatMessageType.TEXT);
        message.setContent("交付物已按要求上传");
        message.setContactHint(0);
        chatMessageMapper.insert(message);

        List<DisputeEvidenceView> evidence = chatEvidenceService.copyFromChat(WORKER, disputeId,
                new ChatEvidenceRequest(List.of(message.getId())));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).content()).isEqualTo("交付物已按要求上传");
        List<DisputeEvidence> rows = disputeEvidenceMapper.selectList(
                new LambdaQueryWrapper<DisputeEvidence>().eq(DisputeEvidence::getDisputeId, disputeId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSubmitterType()).isEqualTo(DisputeInitiatorType.WORKER);
        assertThat(rows.get(0).getSubmitterId()).isEqualTo(WORKER_ID);
        // 复制不是移动：聊天原文仍可读
        assertThat(chatMessageMapper.selectById(message.getId()).getContent())
                .isEqualTo("交付物已按要求上传");

        assertThatThrownBy(() -> chatEvidenceService.copyFromChat(STRANGER, disputeId,
                new ChatEvidenceRequest(List.of(message.getId()))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private FundEscrowDetail escrowDetail(Long slotId) {
        FundEscrowDetail detail = fundEscrowDetailMapper.selectOne(
                new LambdaQueryWrapper<FundEscrowDetail>().eq(FundEscrowDetail::getSlotId, slotId));
        assertThat(detail).isNotNull();
        return detail;
    }

    private Long publishTask() {
        fundAccountService.recharge(OWNER_ID, "SETUP-" + System.nanoTime(), 10_000L);
        Task task = new Task();
        task.setEmployerId(OWNER_ID);
        task.setTitle("V6 争议测试任务-" + System.nanoTime());
        task.setDescription("争议全链路");
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
                DeliverableSubmission.from(List.of("https://img.example.com/v6-dispute.jpg"), "已完成"));
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
