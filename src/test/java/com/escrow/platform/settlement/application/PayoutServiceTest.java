package com.escrow.platform.settlement.application;

import com.escrow.platform.settlement.domain.ChannelOutcome;
import com.escrow.platform.settlement.domain.ChannelResult;
import com.escrow.platform.settlement.domain.PaymentChannel;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 打款编排测试（v4-c4 / v5-c1）：抢占失败不出站、通道异常按未知处理、
 * 金额不一致转人工、重复回调只落一次账。
 *
 * @since V4
 */
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock
    private PayoutTxService payoutTxService;

    @Mock
    private PaymentChannel paymentChannel;

    @Mock
    private PayoutOrderMapper payoutOrderMapper;

    @Mock
    private PayoutRuntimeConfig runtimeConfig;

    @InjectMocks
    private PayoutService payoutService;

    private static final PayoutTxService.PayoutDispatch DISPATCH =
            new PayoutTxService.PayoutDispatch(9L, 8L, "PO9", 9_700L, 1);

    /** 抢不到执行权：不调用通道，也不落结果。 */
    @Test
    void shouldNotCallChannelWhenClaimLoses() {
        when(payoutTxService.claimForDispatch(9L)).thenReturn(null);

        payoutService.dispatch(9L);

        verifyNoInteractions(paymentChannel);
        verify(payoutTxService, never()).handleSuccess(anyLong(), anyLong());
    }

    /** 通道成功：调用 T3 成功并传入请求金额。 */
    @Test
    void shouldHandleSuccess() {
        when(payoutTxService.claimForDispatch(9L)).thenReturn(DISPATCH);
        when(paymentChannel.pay("PO9", 9_700L)).thenReturn(ChannelOutcome.success(9_700L));

        payoutService.dispatch(9L);

        verify(payoutTxService).handleSuccess(9L, 9_700L);
    }

    /** 通道明确失败：走失败分支，不调未知分支。 */
    @Test
    void shouldHandleFail() {
        when(payoutTxService.claimForDispatch(9L)).thenReturn(DISPATCH);
        when(paymentChannel.pay("PO9", 9_700L)).thenReturn(ChannelOutcome.of(ChannelResult.FAIL));

        payoutService.dispatch(9L);

        verify(payoutTxService).handleFail(9L);
        verify(payoutTxService, never()).handleUnknown(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    /** 通道抛异常：按 TIMEOUT 进未知，绝不按失败重试。 */
    @Test
    void shouldTreatChannelExceptionAsUnknown() {
        when(payoutTxService.claimForDispatch(9L)).thenReturn(DISPATCH);
        when(paymentChannel.pay("PO9", 9_700L)).thenThrow(new IllegalStateException("socket closed"));

        payoutService.dispatch(9L);

        verify(payoutTxService).handleUnknown(9L, ChannelResult.TIMEOUT);
        verify(payoutTxService, never()).handleFail(anyLong());
    }

    /** 成功但金额不一致：转人工，既不按请求金额也不按实际金额自动落账。 */
    @Test
    void shouldEscalateAmountMismatchToManualReview() {
        when(payoutTxService.claimForDispatch(9L)).thenReturn(DISPATCH);
        when(paymentChannel.pay("PO9", 9_700L)).thenReturn(ChannelOutcome.amountMismatch(9_600L));

        payoutService.dispatch(9L);

        verify(payoutTxService).handleAmountMismatch(9L, 9_600L);
        verify(payoutTxService, never()).handleSuccess(anyLong(), anyLong());
    }

    /** 重复回调：调用两次 T3，幂等由 T3 的状态判断兜住。 */
    @Test
    void shouldReplayDuplicateCallbackThroughIdempotentT3() {
        when(payoutTxService.claimForDispatch(9L)).thenReturn(DISPATCH);
        when(paymentChannel.pay("PO9", 9_700L)).thenReturn(ChannelOutcome.duplicateSuccess(9_700L));

        payoutService.dispatch(9L);

        verify(payoutTxService, org.mockito.Mockito.times(2)).handleSuccess(9L, 9_700L);
    }
}
