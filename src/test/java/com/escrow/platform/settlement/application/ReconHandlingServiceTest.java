package com.escrow.platform.settlement.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.settlement.api.ReconDecisionRequest;
import com.escrow.platform.settlement.api.ReconDifferenceView;
import com.escrow.platform.settlement.domain.DifferenceResolution;
import com.escrow.platform.settlement.domain.DifferenceStatus;
import com.escrow.platform.settlement.domain.DifferenceType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.SettlementDifference;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementDifferenceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账差异处置测试（V6，FR-RECON.HANDLE.01）：权限、状态、类型绑定、四选一分派。
 *
 * @since V6
 */
@ExtendWith(MockitoExtension.class)
class ReconHandlingServiceTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private SettlementDifferenceMapper settlementDifferenceMapper;
    @Mock
    private PayoutOrderMapper payoutOrderMapper;
    @Mock
    private PayoutTxService payoutTxService;
    @Mock
    private ReconService reconService;

    @Mock
    private com.escrow.platform.common.audit.application.AuditRecorder auditRecorder;
    @InjectMocks
    private ReconHandlingService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, SettlementDifference.class);
        TableInfoHelper.initTableInfo(assistant, PayoutOrder.class);
    }

    private SettlementDifference difference(DifferenceType type) {
        SettlementDifference difference = new SettlementDifference();
        difference.setId(60L);
        difference.setReconDate(LocalDate.now());
        difference.setDiffType(type);
        difference.setLocalRecordNo("PO60");
        difference.setChannelRecordNo(type == DifferenceType.CHANNEL_MISSING ? null : "PO60");
        difference.setLocalAmount(type == DifferenceType.LOST_RESPONSE ? null : 1_000L);
        difference.setChannelAmount(type == DifferenceType.CHANNEL_MISSING ? null : 900L);
        difference.setStatus(DifferenceStatus.SETTLEMENT_DIFF_PENDING);
        difference.setCreatedAt(LocalDateTime.now());
        return difference;
    }

    private SettlementDifference handled(SettlementDifference source, DifferenceResolution resolution) {
        source.setStatus(resolution == DifferenceResolution.RECON_IGNORE
                ? DifferenceStatus.SETTLEMENT_DIFF_IGNORED : DifferenceStatus.SETTLEMENT_DIFF_HANDLED);
        source.setResolution(resolution);
        source.setResolverId(ADMIN.userId());
        source.setResolvedAt(LocalDateTime.now());
        return source;
    }

    private PayoutOrder payout() {
        PayoutOrder payout = new PayoutOrder();
        payout.setId(70L);
        payout.setSettlementId(80L);
        payout.setRequestNo("PO60");
        payout.setAttemptSeq(1);
        payout.setAmount(1_000L);
        payout.setStatus(PayoutStatus.PAYOUT_PENDING_VERIFICATION);
        return payout;
    }

    /** 非管理员：403。 */
    @Test
    void nonAdminShouldBeForbidden() {
        assertThatThrownBy(() -> service.handle(WORKER, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_IGNORE, "无影响")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(settlementDifferenceMapper, never()).selectById(any());
    }

    /** 差异单不存在：404。 */
    @Test
    void missingDifferenceShouldReturnNotFound() {
        when(settlementDifferenceMapper.selectById(60L)).thenReturn(null);

        assertThatThrownBy(() -> service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_IGNORE, "无影响")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 已处置的差异单不能再次处置：409。 */
    @Test
    void nonPendingDifferenceShouldBeRejected() {
        SettlementDifference handled = difference(DifferenceType.LOST_RESPONSE);
        handled.setStatus(DifferenceStatus.SETTLEMENT_DIFF_HANDLED);
        when(settlementDifferenceMapper.selectById(60L)).thenReturn(handled);

        assertThatThrownBy(() -> service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_BOOK, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(settlementDifferenceMapper, never()).update(any(), any());
    }

    /** 类型与动作必须匹配：通道缺失不能补记。 */
    @Test
    void mismatchedResolutionShouldFail() {
        when(settlementDifferenceMapper.selectById(60L))
                .thenReturn(difference(DifferenceType.CHANNEL_MISSING));

        assertThatThrownBy(() -> service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_BOOK, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(payoutTxService, never()).handleReconBook(any(), anyLong());
    }

    /** 忽略必须写说明。 */
    @Test
    void ignoreWithoutRemarkShouldFail() {
        when(settlementDifferenceMapper.selectById(60L))
                .thenReturn(difference(DifferenceType.RESULT_UNDETERMINED));

        assertThatThrownBy(() -> service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_IGNORE, "  ")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 并发抢单失败：409，且不触发资金动作。 */
    @Test
    void lostRaceShouldFailWithoutMovingMoney() {
        when(settlementDifferenceMapper.selectById(60L))
                .thenReturn(difference(DifferenceType.CHANNEL_MISSING));
        when(payoutOrderMapper.selectOne(any())).thenReturn(payout());
        when(settlementDifferenceMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_REVERSE, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(payoutTxService, never()).handleReconReverse(any());
    }

    /** 补记：按通道金额落账，差异单置已处理。 */
    @Test
    void bookShouldDelegateWithChannelAmount() {
        SettlementDifference pending = difference(DifferenceType.LOST_RESPONSE);
        SettlementDifference handled = difference(DifferenceType.LOST_RESPONSE);
        when(settlementDifferenceMapper.selectById(60L)).thenReturn(pending, handled);
        when(payoutOrderMapper.selectOne(any())).thenReturn(payout());
        when(settlementDifferenceMapper.update(any(), any())).thenReturn(1);

        ReconDifferenceView view = service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_BOOK, null));

        verify(payoutTxService).handleReconBook(70L, 900L);
        assertThat(view.status()).isEqualTo(DifferenceStatus.SETTLEMENT_DIFF_PENDING);
    }

    /** 冲正：只传打款单 ID。 */
    @Test
    void reverseShouldDelegate() {
        when(settlementDifferenceMapper.selectById(60L))
                .thenReturn(difference(DifferenceType.CHANNEL_MISSING),
                        handled(difference(DifferenceType.CHANNEL_MISSING),
                                DifferenceResolution.RECON_REVERSE));
        when(payoutOrderMapper.selectOne(any())).thenReturn(payout());
        when(settlementDifferenceMapper.update(any(), any())).thenReturn(1);

        ReconDifferenceView view = service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_REVERSE, null));

        verify(payoutTxService).handleReconReverse(70L);
        assertThat(view.resolution()).isEqualTo(DifferenceResolution.RECON_REVERSE);
    }

    /** 差额调整：本地与通道金额同时传入。 */
    @Test
    void adjustShouldDelegateWithBothAmounts() {
        when(settlementDifferenceMapper.selectById(60L))
                .thenReturn(difference(DifferenceType.AMOUNT_MISMATCH),
                        handled(difference(DifferenceType.AMOUNT_MISMATCH),
                                DifferenceResolution.RECON_ADJUST));
        when(payoutOrderMapper.selectOne(any())).thenReturn(payout());
        when(settlementDifferenceMapper.update(any(), any())).thenReturn(1);

        service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_ADJUST, null));

        verify(payoutTxService).handleReconAdjust(70L, 1_000L, 900L);
    }

    /** 忽略：不触发资金动作，状态置已忽略。 */
    @Test
    void ignoreShouldNotTouchFunds() {
        SettlementDifference pending = difference(DifferenceType.RESULT_UNDETERMINED);
        SettlementDifference ignored = handled(difference(DifferenceType.RESULT_UNDETERMINED),
                DifferenceResolution.RECON_IGNORE);
        when(settlementDifferenceMapper.selectById(60L)).thenReturn(pending, ignored);
        when(settlementDifferenceMapper.update(any(), any())).thenReturn(1);

        ReconDifferenceView view = service.handle(ADMIN, 60L,
                new ReconDecisionRequest(DifferenceResolution.RECON_IGNORE, "通道确认无此笔"));

        verify(payoutTxService, never()).handleReconBook(any(), anyLong());
        verify(payoutTxService, never()).handleReconReverse(any());
        verify(payoutTxService, never()).handleReconAdjust(any(), anyLong(), anyLong());
        assertThat(view.status()).isEqualTo(DifferenceStatus.SETTLEMENT_DIFF_IGNORED);
    }

    /** 对账日为空：手动触发对账 400。 */
    @Test
    void runWithoutDateShouldFail() {
        assertThatThrownBy(() -> service.run(ADMIN, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(reconService, never()).runDaily(any());
    }

    /** 状态参数非法：400，且不查库。 */
    @Test
    void invalidStatusFilterShouldFail() {
        assertThatThrownBy(() -> service.list(ADMIN, "DONE", null, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(settlementDifferenceMapper, never()).selectPage(any(), any());
    }

    /** 手动触发对账：委托 ReconService，权限通过后按日期执行。 */
    @Test
    void runShouldDelegateToReconService() {
        LocalDate date = LocalDate.now();
        ReconRun expected = new ReconRun(1L, date, 1, 1, 0, 0L, true);
        when(reconService.runDaily(eq(date))).thenReturn(expected);

        ReconRun actual = service.run(ADMIN, date);

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(reconService).runDaily(captor.capture());
        assertThat(captor.getValue()).isEqualTo(date);
        assertThat(actual).isSameAs(expected);
    }
}
