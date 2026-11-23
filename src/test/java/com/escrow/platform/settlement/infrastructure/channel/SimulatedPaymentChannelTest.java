package com.escrow.platform.settlement.infrastructure.channel;

import com.escrow.platform.settlement.application.PayoutRuntimeConfig;
import com.escrow.platform.settlement.domain.ChannelResult;
import com.escrow.platform.settlement.domain.ChannelVerification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模拟通道七模式测试（v5-c1）。
 *
 * <p>模式从 {@link PayoutRuntimeConfig} 读取，改配置不需要重建通道——这一条是
 * 「不重启切换模式」的最小证明（完整 Nacos 演示在 v5-c6）。</p>
 *
 * @since V5
 */
class SimulatedPaymentChannelTest {

    private static PayoutRuntimeConfig config(String mode) {
        return new PayoutRuntimeConfig(3, 2, 10, 1, 1, 30, mode);
    }

    private static PayoutRuntimeConfig config() {
        return config("SUCCESS");
    }

    @Test
    void successShouldReturnRequestedAmount() {
        SimulatedPaymentChannel channel = new SimulatedPaymentChannel(config());
        assertThat(channel.pay("PO1", 10_000L).result()).isEqualTo(ChannelResult.SUCCESS);
        assertThat(channel.pay("PO1", 10_000L).actualAmount()).isEqualTo(10_000L);
        assertThat(channel.pay("PO1", 10_000L).duplicateCallback()).isFalse();
    }

    @Test
    void failShouldBeExplicitFailure() {
        assertThat(new SimulatedPaymentChannel(config("fail")).pay("PO2", 1L).result())
                .isEqualTo(ChannelResult.FAIL);
    }

    @Test
    void timeoutAndLostResponseShouldBeUnknownNotFail() {
        assertThat(new SimulatedPaymentChannel(config("TIMEOUT")).pay("PO3", 1L).result())
                .isEqualTo(ChannelResult.TIMEOUT);
        assertThat(new SimulatedPaymentChannel(config("LOST_RESPONSE")).pay("PO3", 1L).result())
                .isEqualTo(ChannelResult.LOST_RESPONSE);
    }

    @Test
    void duplicateCallbackShouldBeFlagged() {
        var outcome = new SimulatedPaymentChannel(config("DUPLICATE_CALLBACK")).pay("PO4", 100L);
        assertThat(outcome.result()).isEqualTo(ChannelResult.SUCCESS);
        assertThat(outcome.duplicateCallback()).isTrue();
    }

    @Test
    void amountMismatchShouldReportActualAmount() {
        var outcome = new SimulatedPaymentChannel(config("AMOUNT_MISMATCH")).pay("PO5", 100L);
        assertThat(outcome.result()).isEqualTo(ChannelResult.SUCCESS);
        assertThat(outcome.actualAmount()).isEqualTo(99L);
        assertThat(outcome.matches(100L)).isFalse();
    }

    @Test
    void channelMissingShouldLookUnknownAndVerifyNotFound() {
        SimulatedPaymentChannel channel = new SimulatedPaymentChannel(config("CHANNEL_MISSING"));
        assertThat(channel.pay("PO6", 100L).result()).isEqualTo(ChannelResult.LOST_RESPONSE);
        assertThat(channel.verify("PO6", 100L).result()).isEqualTo(ChannelVerification.NOT_FOUND);
    }

    @Test
    void verifyShouldDistinguishSuccessFailAndAmountMismatch() {
        SimulatedPaymentChannel success = new SimulatedPaymentChannel(config());
        success.pay("PO7", 100L);
        assertThat(success.verify("PO7", 100L).result()).isEqualTo(ChannelVerification.SUCCESS);
        assertThat(success.verify("PO7", 101L).result()).isEqualTo(ChannelVerification.AMOUNT_MISMATCH);

        SimulatedPaymentChannel fail = new SimulatedPaymentChannel(config("FAIL"));
        fail.pay("PO8", 100L);
        assertThat(fail.verify("PO8", 100L).result()).isEqualTo(ChannelVerification.FAIL);

        assertThat(success.verify("PO-NOT-EXIST", 100L).result()).isEqualTo(ChannelVerification.NOT_FOUND);
    }

    @Test
    void sameRequestNoShouldReturnFirstResult() {
        SimulatedPaymentChannel channel = new SimulatedPaymentChannel(config());
        assertThat(channel.pay("PO4", 1L).actualAmount()).isEqualTo(1L);
        assertThat(channel.pay("PO4", 999L).actualAmount()).isEqualTo(1L);
    }

    @Test
    void gatewayModeShouldBeReadPerCall() {
        PayoutRuntimeConfig runtime = config("SUCCESS");
        SimulatedPaymentChannel channel = new SimulatedPaymentChannel(runtime);
        assertThat(channel.pay("PO9", 10L).result()).isEqualTo(ChannelResult.SUCCESS);

        runtime.apply(java.util.Map.of("gateway.mode", "FAIL"));
        assertThat(channel.pay("PO10", 10L).result()).isEqualTo(ChannelResult.FAIL);
    }

    @Test
    void invalidModeShouldFailFast() {
        assertThatThrownBy(() -> new SimulatedPaymentChannel(config("MAYBE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway-mode");
    }
}
