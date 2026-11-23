package com.escrow.platform.dispute.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.dispute.api.DisputeEvidenceRequest;
import com.escrow.platform.dispute.api.DisputeEvidenceView;
import com.escrow.platform.dispute.api.DisputeSummaryView;
import com.escrow.platform.dispute.api.DisputeView;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeEvidence;
import com.escrow.platform.dispute.domain.DisputeInitiatorType;
import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeEvidenceMapper;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeMapper;
import com.escrow.platform.engagement.application.ContractQueryService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.task.application.TaskQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 争议读写测试（V6）：复用撤回单、撤回次数上限、证据与可见性、管理端列表与超期。
 *
 * @since V6
 */
@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    private static final long SLOT_ID = 9L;
    private static final long TASK_ID = 8L;
    private static final long CONTRACT_ID = 7L;
    private static final long DISPUTE_ID = 60L;

    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser OWNER =
            new LoginUser(2001L, "owner@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser STRANGER =
            new LoginUser(3001L, "stranger@example.com", UserRole.WORKER, IdentityType.WORKER);
    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Mock
    private DisputeMapper disputeMapper;
    @Mock
    private DisputeEvidenceMapper disputeEvidenceMapper;
    @Mock
    private ContractQueryService contractQueryService;
    @Mock
    private TaskQueryService taskQueryService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DisputeService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Dispute.class);
        TableInfoHelper.initTableInfo(assistant, DisputeEvidence.class);
    }

    private Dispute dispute(DisputeStatus status, DisputeInitiatorType initiator) {
        Dispute dispute = new Dispute();
        dispute.setId(DISPUTE_ID);
        dispute.setSlotId(SLOT_ID);
        dispute.setInitiatorType(initiator);
        dispute.setReason("测试争议");
        dispute.setStatus(status);
        dispute.setWithdrawCount(0);
        dispute.setCreatedAt(LocalDateTime.now());
        return dispute;
    }

    private void partyWorker(long userId) {
        when(contractQueryService.contractOfSlot(SLOT_ID))
                .thenReturn(new ContractQueryService.ContractBrief(CONTRACT_ID, TASK_ID, SLOT_ID, userId));
    }

    /** FR-DISPUTE.OPEN.01：无历史撤回单时插入新单。 */
    @Test
    void openOrReuseShouldInsertWhenNoWithdrawn() {
        when(disputeMapper.selectCount(any())).thenReturn(0L);
        when(disputeMapper.selectOne(any())).thenReturn(null);
        when(disputeMapper.insert(any(Dispute.class))).thenAnswer(invocation -> {
            invocation.<Dispute>getArgument(0).setId(DISPUTE_ID);
            return 1;
        });

        Long id = service.openOrReuse(SLOT_ID, DisputeInitiatorType.WORKER, "  交付不公  ");

        assertThat(id).isEqualTo(DISPUTE_ID);
        verify(disputeMapper).insert(any(Dispute.class));
    }

    /** FR-DISPUTE.WITHDRAW.02：存在撤回单时复用，返回旧 ID 且不再插入。 */
    @Test
    void openOrReuseShouldReopenWithdrawn() {
        when(disputeMapper.selectCount(any())).thenReturn(0L);
        when(disputeMapper.selectOne(any()))
                .thenReturn(dispute(DisputeStatus.DISPUTE_WITHDRAWN, DisputeInitiatorType.WORKER));
        when(disputeMapper.update(any(), any())).thenReturn(1);

        Long id = service.openOrReuse(SLOT_ID, DisputeInitiatorType.EMPLOYER, null);

        assertThat(id).isEqualTo(DISPUTE_ID);
        verify(disputeMapper, never()).insert(any(Dispute.class));
    }

    /** 已有未决争议时不得重复开启。 */
    @Test
    void openOrReuseShouldRejectActiveDispute() {
        when(disputeMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.openOrReuse(SLOT_ID, DisputeInitiatorType.WORKER, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DISPUTE_ACTIVE);
    }

    /** FR-DISPUTE.WITHDRAW.01：条件更新失败说明状态已变或次数用尽。 */
    @Test
    void withdrawShouldRejectWhenConditionUpdateMisses() {
        when(disputeMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.withdraw(dispute(DisputeStatus.DISPUTE_PENDING,
                DisputeInitiatorType.WORKER)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    /** 撤回成功后返回累加后的次数。 */
    @Test
    void withdrawShouldReturnIncrementedCount() {
        when(disputeMapper.update(any(), any())).thenReturn(1);
        Dispute withdrawn = dispute(DisputeStatus.DISPUTE_WITHDRAWN, DisputeInitiatorType.WORKER);
        withdrawn.setWithdrawCount(1);
        when(disputeMapper.selectById(DISPUTE_ID)).thenReturn(withdrawn);

        Dispute result = service.withdraw(dispute(DisputeStatus.DISPUTE_PENDING,
                DisputeInitiatorType.WORKER));

        assertThat(result.getStatus()).isEqualTo(DisputeStatus.DISPUTE_WITHDRAWN);
        assertThat(result.getWithdrawCount()).isEqualTo(1);
    }

    /** FR-DISPUTE.EVIDENCE.01：非当事人不得查看。 */
    @Test
    void detailShouldRejectStranger() {
        when(disputeMapper.selectById(DISPUTE_ID))
                .thenReturn(dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER));
        partyWorker(WORKER.userId());
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(OWNER.userId());

        assertThatThrownBy(() -> service.detail(STRANGER, DISPUTE_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** 雇主与劳动者都能看到同一份详情与证据。 */
    @Test
    void detailShouldAllowBothParties() {
        when(disputeMapper.selectById(DISPUTE_ID))
                .thenReturn(dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.EMPLOYER));
        partyWorker(WORKER.userId());
        when(taskQueryService.employerIdOf(TASK_ID)).thenReturn(OWNER.userId());
        when(disputeEvidenceMapper.selectList(any())).thenReturn(List.of());

        DisputeView workerView = service.detail(WORKER, DISPUTE_ID);
        DisputeView ownerView = service.detail(OWNER, DISPUTE_ID);

        assertThat(workerView.id()).isEqualTo(DISPUTE_ID);
        assertThat(ownerView.evidence()).isEmpty();
    }

    /** 文字与图片都为空时拒绝。 */
    @Test
    void addEvidenceShouldRejectEmptyContent() {
        when(disputeMapper.selectById(DISPUTE_ID))
                .thenReturn(dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER));
        partyWorker(WORKER.userId());

        assertThatThrownBy(() -> service.addEvidence(WORKER, DISPUTE_ID,
                new DisputeEvidenceRequest("  ", List.of())))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 争议已裁决后不可再补证据。 */
    @Test
    void addEvidenceShouldRejectResolvedDispute() {
        when(disputeMapper.selectById(DISPUTE_ID))
                .thenReturn(dispute(DisputeStatus.DISPUTE_RESOLVED, DisputeInitiatorType.WORKER));
        partyWorker(WORKER.userId());

        assertThatThrownBy(() -> service.addEvidence(WORKER, DISPUTE_ID,
                new DisputeEvidenceRequest("补充说明", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    /** 证据落库：文字去空格、图片序列化为 JSON 文本。 */
    @Test
    void addEvidenceShouldPersistContentAndImages() {
        when(disputeMapper.selectById(DISPUTE_ID))
                .thenReturn(dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER));
        partyWorker(WORKER.userId());
        when(disputeEvidenceMapper.insert(any(DisputeEvidence.class))).thenAnswer(invocation -> {
            invocation.<DisputeEvidence>getArgument(0).setId(70L);
            return 1;
        });

        DisputeEvidenceView view = service.addEvidence(WORKER, DISPUTE_ID,
                new DisputeEvidenceRequest("  已按约定交付  ",
                        List.of("https://img.example.com/a.jpg")));

        assertThat(view.id()).isEqualTo(70L);
        assertThat(view.content()).isEqualTo("已按约定交付");
        assertThat(view.imageUrls()).containsExactly("https://img.example.com/a.jpg");
        assertThat(view.submitterType()).isEqualTo(DisputeInitiatorType.WORKER);
    }

    /** 管理端列表只对管理员开放。 */
    @Test
    void listForAdminShouldRejectNonAdmin() {
        assertThatThrownBy(() -> service.listForAdmin(WORKER, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /** FR-DISPUTE.CLOSE.02：超期列表返回等待天数。 */
    @Test
    void overdueShouldReturnPendingDays() {
        Dispute pending = dispute(DisputeStatus.DISPUTE_PENDING, DisputeInitiatorType.WORKER);
        pending.setCreatedAt(LocalDateTime.now().minusDays(9));
        when(disputeMapper.selectList(any())).thenReturn(List.of(pending));

        List<DisputeSummaryView> overdue = service.overdue(ADMIN, 7);

        assertThat(overdue).hasSize(1);
        assertThat(overdue.get(0).pendingDays()).isGreaterThanOrEqualTo(9);
    }
}
