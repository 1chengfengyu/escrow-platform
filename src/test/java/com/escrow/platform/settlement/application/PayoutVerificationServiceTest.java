package com.escrow.platform.settlement.application;

import com.escrow.platform.settlement.domain.ChannelVerification;
import com.escrow.platform.settlement.domain.ChannelVerificationResult;
import com.escrow.platform.settlement.domain.PaymentChannel;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.PayoutVerificationRecord;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutVerificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 未知态查证测试（v5-c2）：五种结论分别处置，且只有 NOT_FOUND / FAIL 才允许判失败。
 *
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
class PayoutVerificationServiceTest {

    @Mock
    private PayoutOrderMapper payoutOrderMapper;
    @Mock
    private PayoutVerificationMapper payoutVerificationMapper;
    @Mock
    private PaymentChannel paymentChannel;
    @Mock
    private PayoutTxService payoutTxService;
    @Mock
    private PayoutRuntimeConfig runtimeConfig;

    @InjectMocks
    private PayoutVerificationService service;

    private PayoutOrder unknownPayout() {
        PayoutOrder payout = new PayoutOrder();
        payout.setId(7L);
        payout.setSettlementId(8L);
        payout.setRequestNo("PO7");
        payout.setAmount(9_700L);
        payout.setAttemptSeq(1);
        payout.setStatus(PayoutStatus.PAYOUT_PENDING_VERIFICATION);
        return payout;
    }

    private PayoutVerificationRecord captureRecord() {
        ArgumentCaptor<PayoutVerificationRecord> captor =
                ArgumentCaptor.forClass(PayoutVerificationRecord.class);
        verify(payoutVerificationMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 查到且金额一致：按成功落账。 */
    @Test
    void verifiedSuccessShouldCompleteSettlement() {
        when(payoutOrderMapper.selectById(7L)).thenReturn(unknownPayout());
        when(paymentChannel.verify("PO7", 9_700L))
                .thenReturn(ChannelVerificationResult.success(9_700L));

        assertThat(service.verify(7L)).isEqualTo(ChannelVerification.SUCCESS);

        verify(payoutTxService).handleVerifiedSuccess(7L);
        assertThat(captureRecord().getResult()).isEqualTo(ChannelVerification.SUCCESS);
    }

    /** 通道确认没这笔：判失败并允许重试。 */
    @Test
    void notFoundShouldBeTreatedAsFailure() {
        when(payoutOrderMapper.selectById(7L)).thenReturn(unknownPayout());
        when(paymentChannel.verify("PO7", 9_700L))
                .thenReturn(ChannelVerificationResult.notFound());

        assertThat(service.verify(7L)).isEqualTo(ChannelVerification.NOT_FOUND);

        verify(payoutTxService).handleVerificationFailed(7L);
        assertThat(captureRecord().getResult()).isEqualTo(ChannelVerification.NOT_FOUND);
    }

    /** 通道明确失败：同样按失败处置。 */
    @Test
    void channelFailShouldBeTreatedAsFailure() {
        when(payoutOrderMapper.selectById(7L)).thenReturn(unknownPayout());
        when(paymentChannel.verify("PO7", 9_700L)).thenReturn(ChannelVerificationResult.fail());

        service.verify(7L);

        verify(payoutTxService).handleVerificationFailed(7L);
    }

    /** 金额不一致：转人工并带上通道实际金额。 */
    @Test
    void amountMismatchShouldEscalateWithActualAmount() {
        when(payoutOrderMapper.selectById(7L)).thenReturn(unknownPayout());
        when(paymentChannel.verify("PO7", 9_700L))
                .thenReturn(ChannelVerificationResult.amountMismatch(9_600L));

        service.verify(7L);

        verify(payoutTxService).handleVerifiedAmountMismatch(7L, 9_600L);
        assertThat(captureRecord().getActualAmount()).isEqualTo(9_600L);
    }

    /** 查证调用本身失败：保持未知，绝不推进状态。 */
    @Test
    void verificationErrorShouldKeepUnknownState() {
        when(payoutOrderMapper.selectById(7L)).thenReturn(unknownPayout());
        when(paymentChannel.verify("PO7", 9_700L)).thenThrow(new IllegalStateException("timeout"));

        assertThat(service.verify(7L)).isEqualTo(ChannelVerification.ERROR);

        verifyNoInteractions(payoutTxService);
        assertThat(captureRecord().getResult()).isEqualTo(ChannelVerification.ERROR);
    }

    /** 非待查证的单据不查证、不落流水。 */
    @Test
    void nonUnknownPayoutShouldBeSkipped() {
        PayoutOrder payout = unknownPayout();
        payout.setStatus(PayoutStatus.PAYOUT_SUCCESS);
        when(payoutOrderMapper.selectById(7L)).thenReturn(payout);

        assertThat(service.verify(7L)).isNull();

        verify(paymentChannel, never()).verify(any(), anyLong());
        verify(payoutVerificationMapper, never()).insert(any(PayoutVerificationRecord.class));
    }
}
