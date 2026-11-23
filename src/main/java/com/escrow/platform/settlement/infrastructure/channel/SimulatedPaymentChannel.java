package com.escrow.platform.settlement.infrastructure.channel;

import com.escrow.platform.settlement.application.PayoutRuntimeConfig;
import com.escrow.platform.settlement.domain.ChannelMode;
import com.escrow.platform.settlement.domain.ChannelOutcome;
import com.escrow.platform.settlement.domain.ChannelResult;
import com.escrow.platform.settlement.domain.ChannelVerification;
import com.escrow.platform.settlement.domain.ChannelVerificationResult;
import com.escrow.platform.settlement.domain.PaymentChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟支付通道（V4 四模式 → V5 七模式）。
 *
 * <p>模式从 {@link PayoutRuntimeConfig} <b>每次调用时读取</b>，因此改配置后下一笔打款
 * 立即按新模式执行，不需要重启（CR-13）。</p>
 *
 * <p><b>请求号去重</b>：同一 {@code requestNo} 重复 {@code pay} 返回首次结果，模拟真实通道
 * 按幂等键去重；{@code verify} 只读这份记录，绝不产生新出款。</p>
 *
 * <p><b>CHANNEL_MISSING</b> 的语义：出站时像「应答丢失」一样拿不到结果，且通道侧也查不到记录；
 * 查证返回 {@code NOT_FOUND}，据此判失败并重试是安全的。真正的「本地成功而通道无记录」
 * 只能靠 V6 对账发现，本类不伪造这种情况。</p>
 *
 * @since V4
 */
@Slf4j
@Component
public class SimulatedPaymentChannel implements PaymentChannel {

    private final PayoutRuntimeConfig runtimeConfig;
    private final Map<String, ChannelOutcome> answered = new ConcurrentHashMap<>();
    private final Set<String> missingAtChannel = ConcurrentHashMap.newKeySet();

    /**
     * @param runtimeConfig 运行期参数（含 {@code gateway.mode}）
     */
    public SimulatedPaymentChannel(PayoutRuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
        log.info("模拟支付通道已启用 mode={}", runtimeConfig.gatewayMode());
    }

    @Override
    public ChannelOutcome pay(String requestNo, long amount) {
        ChannelOutcome outcome = answered.computeIfAbsent(requestNo, no -> simulate(no, amount));
        if (outcome.duplicateCallback()) {
            log.info("模拟通道将重复投递回调 requestNo={}", requestNo);
        }
        return outcome;
    }

    @Override
    public ChannelVerificationResult verify(String requestNo, long expectedAmount) {
        if (missingAtChannel.contains(requestNo)) {
            return ChannelVerificationResult.notFound();
        }
        ChannelOutcome outcome = answered.get(requestNo);
        if (outcome == null) {
            return ChannelVerificationResult.notFound();
        }
        return switch (outcome.result()) {
            case SUCCESS -> outcome.actualAmount() == expectedAmount
                    ? ChannelVerificationResult.success(outcome.actualAmount())
                    : ChannelVerificationResult.amountMismatch(outcome.actualAmount());
            case FAIL -> ChannelVerificationResult.fail();
            case TIMEOUT, LOST_RESPONSE -> ChannelVerificationResult.notFound();
        };
    }

    private ChannelOutcome simulate(String requestNo, long amount) {
        ChannelMode mode = runtimeConfig.gatewayMode();
        log.info("模拟通道出站 requestNo={} amount={} mode={}", requestNo, amount, mode);
        return switch (mode) {
            case SUCCESS -> ChannelOutcome.success(amount);
            case FAIL -> ChannelOutcome.of(ChannelResult.FAIL);
            case TIMEOUT -> ChannelOutcome.of(ChannelResult.TIMEOUT);
            case LOST_RESPONSE -> ChannelOutcome.of(ChannelResult.LOST_RESPONSE);
            case DUPLICATE_CALLBACK -> ChannelOutcome.duplicateSuccess(amount);
            case CHANNEL_MISSING -> {
                missingAtChannel.add(requestNo);
                yield ChannelOutcome.of(ChannelResult.LOST_RESPONSE);
            }
            case AMOUNT_MISMATCH -> ChannelOutcome.amountMismatch(amount > 0 ? amount - 1 : 0);
        };
    }
}
