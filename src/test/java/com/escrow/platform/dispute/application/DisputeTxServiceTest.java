package com.escrow.platform.dispute.application;

import com.escrow.platform.common.chat.application.ChatEventDispatcher;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.engagement.application.ContractService;
import com.escrow.platform.fund.application.EscrowService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.application.SettlementService;
import com.escrow.platform.task.application.TaskQueryService;
import com.escrow.platform.task.application.TaskSlotOccupancyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 争议事务测试（V6）：申诉权限与冻结、撤回还原、裁决放款 / 退款与零实付边界。
 *
 * @since V6
 */
@ExtendWith(MockitoExtension.class)
class DisputeTxServiceTest {

    private static final long SLOT_ID = 9L;
    private static final long TASK_ID = 8L;
    private static final long CONTRACT_ID = 7L;
    private static final long DISPUTE_ID = 60L;
    private static final long WORKER_ID = 1001L;
    private static final long EMPLOYER_ID = 2001L;

    private static final LoginUser WORKER =
            new LoginUser(WORKER_ID, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser OTHER_WORKER =
            new LoginUser(1002L, "other@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Mock
    private DisputeService disputeService;
    @Mock
    private ContractQueryService contractQueryService;
    @Mock
    private ContractService contractService;
    @Mock
    private TaskQueryService taskQueryService;
    @Mock
    private TaskSlotOccupancyService taskSlotOccupancyService;
    @Mock
    private EscrowService escrowService;
    @Mock
    private SettlementService settlementService;

    @Mock
    private com.escrow.platform.common.audit.application.AuditRecorder auditRecorder;
    @Mock
    private ChatEventDispatcher chatEventDispatcher;
    @InjectMocks
    private DisputeTxService service;

    private ContractQueryService.ContractBrief contract(long workerId) {
        return new ContractQueryService.ContractBrief(CONTRACT_ID, TASK_ID, SLOT_ID, workerId);
    }

    private Dispute dispute(DisputeStatus status, DisputeInitiatorType initiator) {
        Dispute dispute = new Dispute();
        dispute.setId(DISPUTE_ID);
        dispute.setSlotId(SLOT_ID);
        dispute.setStatus(status);
        dispute.setInitiatorType(initiator);
        dispute.setReason("测试争议");
        return dispute;
    }

    /** FR-DISPUTE.OPEN.01：非签约劳动者不能申诉。 */
    @Test
    void appealShouldRejectNonOwner() {
        when(contractQueryService.contractOf(CONTRACT_ID)).thenReturn(contract(WORKER_ID));

        assertThatThrownBy(() -> service.openWorkerAppeal(OTHER_WORKER, CONTRACT_ID, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(taskSlotOccupancyService, never()).openArbitration(anyLong(), anyLong());
    }

    /** 签约不存在返回 404。 */
    @Test
    void appealShouldRejectMissingContract() {
        when(contractQueryService.contractOf(CONTRACT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.openWorkerAppeal(WORKER, CONTRACT_ID, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 已有未决争议不得重复申诉。 */
    @Test
    void appealShouldRejectActiveDispute() {
        when(contractQueryService.contractOf(CONTRACT_ID)).thenReturn(contract(WORKER_ID));
        when(disputeService.hasOpenDispute(SLOT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.openWorkerAppeal(WORKER, CONTRACT_ID, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DISPUTE_ACTIVE);
    }

    /** 非已交付名额申诉被拒，异常转成 409。 */
    @Test
    void appealShouldMapIllegalStateToConflict() {
        when(contractQueryService.contractOf(CONTRACT_ID)).thenReturn(contract(WORKER_ID));
        when(disputeService.hasOpenDispute(SLOT_ID)).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("名额不在已交付"))
                .when(taskSlotOccupancyService).openArbitration(TASK_ID, SLOT_ID);

        assertThatThrownBy(() -> service.openWorkerAppeal(WORKER, CONTRACT_ID, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    /** 申诉成功：名额转待裁决、托管冻结、争议单开启。 */
    @Test
    void appealShouldFreezeAndOpenDispute() {
        when(contractQueryService.contractOf(CONTRACT_ID)).thenReturn(contract(WORKER_ID));
        when(disputeService.hasOpenDispute(SLOT_ID)).thenReturn(false);
        when(disputeService.openOrReuse(SLOT_ID, DisputeInitiatorType.WORKER, "交付不公"))
                .thenReturn(DISPUTE_ID);

        Long id = service.openWorkerAppeal(WORKER, CONTRACT_ID, "交付不公");

        assertThat(id).isEqualTo(DISPUTE_ID);
        var order = inOrder(taskSlotOccupancyService, escrowService, disputeService);
        order.verify(taskSlotOccupancyService).openArbitration(TASK_ID, SLOT_ID);
        order.verify(escrowService).freezeForDispute(SLOT_ID);
        order.verify(disputeService).openOrReuse(SLOT_ID, DisputeInitiatorType.WORKER, "交付不公");
    }

    /** FR-DISPUTE.WITHDRAW.01：非发起方不得撤回。 */
    @Test
    void withdrawShouldRejectDifferentInitiator() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.EMPLOYER);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);

        assertThatThrownBy(() -> service.withdraw(WORKER, DISPUTE_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 发起方也必须是当事人。 */
    @Test
    void withdrawShouldRejectNonParty() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(false);

        assertThatThrownBy(() -> service.withdraw(WORKER, DISPUTE_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 撤回成功：名额回已交付、托管解冻、验收倒计时重算。 */
    @Test
    void withdrawShouldRestoreSlotAndEscrow() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(true);
        when(contractQueryService.contractOfSlot(SLOT_ID)).thenReturn(contract(WORKER_ID));

        service.withdraw(WORKER, DISPUTE_ID);

        var order = inOrder(disputeService, taskSlotOccupancyService, escrowService, contractService);
        order.verify(disputeService).withdraw(dispute);
        order.verify(taskSlotOccupancyService).withdrawArbitration(TASK_ID, SLOT_ID);
        order.verify(escrowService).unfreezeAfterDispute(SLOT_ID);
        order.verify(contractService).restartAcceptanceCountdown(CONTRACT_ID);
    }

    /** 仅管理员可裁决。 */
    @Test
    void resolveShouldRejectNonAdmin() {
        assertThatThrownBy(() -> service.resolve(WORKER, DISPUTE_ID, DisputeResolution.RELEASE, "理由"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 裁决依据必填。 */
    @Test
    void resolveShouldRejectBlankBasis() {
        assertThatThrownBy(() -> service.resolve(ADMIN, DISPUTE_ID, DisputeResolution.REFUND, "  "))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(disputeService, never()).markResolved(any(), any(), any(), any());
    }

    /** 不在待裁决时拒绝。 */
    @Test
    void resolveShouldRejectResolvedDispute() {
        when(disputeService.require(DISPUTE_ID))
                .thenReturn(dispute(DisputeStatus.DISPUTE_RESOLVED, DisputeInitiatorType.EMPLOYER));

        assertThatThrownBy(() -> service.resolve(ADMIN, DISPUTE_ID, DisputeResolution.RELEASE, "理由"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    /** FR-DISPUTE.RESOLVE.01（放款）：解冻 → 验收 → 结算 → 名额待结算，返回打款单。 */
    @Test
    void resolveReleaseShouldSettleAndRelease() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.EMPLOYER);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(contractQueryService.contractOfSlot(SLOT_ID)).thenReturn(contract(WORKER_ID));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER_ID);
        when(settlementService.settleAccepted(any())).thenReturn(
                new SettlementService.SettlementOutcome(11L, "ST11", 1_000L, 30L, 970L, 700L));

        DisputeTxService.ResolutionOutcome outcome =
                service.resolve(ADMIN, DISPUTE_ID, DisputeResolution.RELEASE, " 证据充分 ");

        assertThat(outcome.payoutId()).isEqualTo(700L);
        verify(disputeService).markResolved(DISPUTE_ID, DisputeResolution.RELEASE, "证据充分",
                ADMIN.userId());
        var order = inOrder(escrowService, contractService, settlementService, taskSlotOccupancyService);
        order.verify(escrowService).unfreezeAfterDispute(SLOT_ID);
        order.verify(contractService).completeAfterArbitration(CONTRACT_ID);
        order.verify(settlementService).settleAccepted(any());
        order.verify(taskSlotOccupancyService).releaseArbitration(TASK_ID, SLOT_ID);
    }

    /** 零实付边界：名额直接完结，不进入待结算。 */
    @Test
    void resolveReleaseWithZeroNetShouldComplete() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.EMPLOYER);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(contractQueryService.contractOfSlot(SLOT_ID)).thenReturn(contract(WORKER_ID));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER_ID);
        when(settlementService.settleAccepted(any())).thenReturn(
                new SettlementService.SettlementOutcome(11L, "ST11", 0L, 0L, 0L, null));

        service.resolve(ADMIN, DISPUTE_ID, DisputeResolution.RELEASE, "无需支付");

        verify(taskSlotOccupancyService).completeArbitration(TASK_ID, SLOT_ID);
        verify(taskSlotOccupancyService, never()).releaseArbitration(anyLong(), anyLong());
    }

    /** FR-DISPUTE.RESOLVE.01（退款）：冻结金额退回雇主，签约取消、名额已退款。 */
    @Test
    void resolveRefundShouldRefundEmployerAndCancelContract() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.EMPLOYER);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(contractQueryService.contractOfSlot(SLOT_ID)).thenReturn(contract(WORKER_ID));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER_ID);
        when(escrowService.refundFrozenSlot(SLOT_ID, DISPUTE_ID, EMPLOYER_ID)).thenReturn(1_000L);

        DisputeTxService.ResolutionOutcome outcome =
                service.resolve(ADMIN, DISPUTE_ID, DisputeResolution.REFUND, "未按约定交付");

        assertThat(outcome.payoutId()).isNull();
        verify(escrowService).refundFrozenSlot(SLOT_ID, DISPUTE_ID, EMPLOYER_ID);
        verify(taskSlotOccupancyService).refundArbitration(TASK_ID, SLOT_ID);
        verify(contractService).cancelAfterArbitration(CONTRACT_ID);
        // CARD-11：退款后无名额可推进（默认 false）→ 登记归档
        verify(chatEventDispatcher).archiveByTaskAfterCommit(TASK_ID);
        verify(settlementService, never()).settleAccepted(any());
    }

    /** 裁决找不到签约属于数据不一致。 */
    @Test
    void resolveShouldFailWhenContractMissing() {
        when(disputeService.require(DISPUTE_ID))
                .thenReturn(dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER));
        when(contractQueryService.contractOfSlot(SLOT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.resolve(ADMIN, DISPUTE_ID, DisputeResolution.RELEASE, "理由"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 撤回找不到签约属于数据不一致。 */
    @Test
    void withdrawShouldFailWhenContractMissing() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(disputeService.isParty(WORKER, dispute)).thenReturn(true);
        when(contractQueryService.contractOfSlot(SLOT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.withdraw(WORKER, DISPUTE_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 管理员不能作为发起方撤回。 */
    @Test
    void withdrawShouldRejectAdmin() {
        when(disputeService.require(DISPUTE_ID))
                .thenReturn(dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER));

        assertThatThrownBy(() -> service.withdraw(ADMIN, DISPUTE_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 结算入参携带名额、雇主、劳动者与 OPERATOR 触发方。 */
    @Test
    void resolveReleaseShouldPassOperatorTrigger() {
        Dispute dispute = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.EMPLOYER);
        when(disputeService.require(DISPUTE_ID)).thenReturn(dispute);
        when(contractQueryService.contractOfSlot(SLOT_ID)).thenReturn(contract(WORKER_ID));
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(EMPLOYER_ID);
        when(settlementService.settleAccepted(any())).thenReturn(
                new SettlementService.SettlementOutcome(11L, "ST11", 1_000L, 30L, 970L, 700L));

        service.resolve(ADMIN, DISPUTE_ID, DisputeResolution.RELEASE, "放款");

        verify(settlementService).settleAccepted(eq(new SettlementService.SettlementRequest(
                SLOT_ID, EMPLOYER_ID, WORKER_ID,
                com.escrow.platform.fund.domain.JournalTriggerType.OPERATOR)));
    }
}
