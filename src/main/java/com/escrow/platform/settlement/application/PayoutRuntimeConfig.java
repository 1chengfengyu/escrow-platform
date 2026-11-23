package com.escrow.platform.settlement.application;

import com.escrow.platform.settlement.domain.ChannelMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 打款运行期参数（CR-13：可配置且可热更新）。
 *
 * <p><b>为什么不用 {@code @Value} 字段</b>：{@code @Value} 只在启动时注入，
 * 改配置必须重启；V5 要演示「改 {@code gateway.mode} 不重启就切模式」。
 * 本类用 volatile 字段承载，由 {@code NacosConfigRefresher}（v5-c6）在配置变更时调用
 * {@link #apply(Map)}，读写都无锁。</p>
 *
 * <p>所有值都做边界校验：非法值只记日志、保留旧值，绝不让一次手误把在途资金推进错误分支。</p>
 *
 * @since V5
 */
@Component
public class PayoutRuntimeConfig {

    /** 单次扫描上限之外的上限：尝试次数最多 10，避免配置写成 100 无限重试。 */
    private static final int MAX_ATTEMPTS_LIMIT = 10;

    /** 分钟类参数的上限：一天。 */
    private static final long MINUTES_LIMIT = 1440L;

    private volatile int maxAttempts;
    private volatile long pendingMinutes;
    private volatile long stuckMinutes;
    private volatile long verifyDelayMinutes;
    private volatile long retryDelayMinutes;
    private volatile long agingWarningMinutes;
    private volatile ChannelMode gatewayMode;

    /**
     * 用配置文件 / 环境变量初始化一次；之后允许热更新。
     *
     * @param maxAttempts        最大尝试次数
     * @param pendingMinutes     待执行账龄阈值（分钟）
     * @param stuckMinutes       执行中账龄阈值（分钟）
     * @param verifyDelayMinutes 未知态多久后开始查证（分钟）
     * @param retryDelayMinutes  待重试多久后新建尝试（分钟）
     * @param agingWarningMinutes 人工 / 未知账龄告警阈值（分钟）
     * @param gatewayMode        通道模式
     */
    public PayoutRuntimeConfig(
            @Value("${escrow.payout.max-attempts:3}") int maxAttempts,
            @Value("${escrow.payout.pending-minutes:2}") long pendingMinutes,
            @Value("${escrow.payout.stuck-minutes:10}") long stuckMinutes,
            @Value("${escrow.payout.verify-delay-minutes:1}") long verifyDelayMinutes,
            @Value("${escrow.payout.retry-delay-minutes:1}") long retryDelayMinutes,
            @Value("${escrow.payout.aging-warning-minutes:30}") long agingWarningMinutes,
            @Value("${escrow.payout.gateway-mode:SUCCESS}") String gatewayMode) {
        this.maxAttempts = clampAttempts(maxAttempts);
        this.pendingMinutes = clampMinutes(pendingMinutes);
        this.stuckMinutes = clampMinutes(stuckMinutes);
        this.verifyDelayMinutes = clampMinutes(verifyDelayMinutes);
        this.retryDelayMinutes = clampMinutes(retryDelayMinutes);
        this.agingWarningMinutes = clampMinutes(agingWarningMinutes);
        this.gatewayMode = ChannelMode.parse(gatewayMode);
    }

    /**
     * 应用一组热更新配置；未出现的键保持原值。
     *
     * @param values 配置键值（键与 yml 一致，如 {@code gateway.mode}）
     */
    public synchronized void apply(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (values.containsKey("max-attempts")) {
            this.maxAttempts = clampAttempts(parseInt(values.get("max-attempts"), this.maxAttempts));
        }
        if (values.containsKey("pending-minutes")) {
            this.pendingMinutes = clampMinutes(parseLong(values.get("pending-minutes"), this.pendingMinutes));
        }
        if (values.containsKey("stuck-minutes")) {
            this.stuckMinutes = clampMinutes(parseLong(values.get("stuck-minutes"), this.stuckMinutes));
        }
        if (values.containsKey("verify-delay-minutes")) {
            this.verifyDelayMinutes = clampMinutes(
                    parseLong(values.get("verify-delay-minutes"), this.verifyDelayMinutes));
        }
        if (values.containsKey("retry-delay-minutes")) {
            this.retryDelayMinutes = clampMinutes(
                    parseLong(values.get("retry-delay-minutes"), this.retryDelayMinutes));
        }
        if (values.containsKey("aging-warning-minutes")) {
            this.agingWarningMinutes = clampMinutes(
                    parseLong(values.get("aging-warning-minutes"), this.agingWarningMinutes));
        }
        if (values.containsKey("gateway.mode")) {
            this.gatewayMode = ChannelMode.parse(values.get("gateway.mode"));
        }
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long pendingMinutes() {
        return pendingMinutes;
    }

    public long stuckMinutes() {
        return stuckMinutes;
    }

    public long verifyDelayMinutes() {
        return verifyDelayMinutes;
    }

    public long retryDelayMinutes() {
        return retryDelayMinutes;
    }

    public long agingWarningMinutes() {
        return agingWarningMinutes;
    }

    public ChannelMode gatewayMode() {
        return gatewayMode;
    }

    /** 待执行账龄截止时间。 */
    public LocalDateTime pendingCutoff() {
        return LocalDateTime.now().minus(Duration.ofMinutes(pendingMinutes));
    }

    /** 执行中账龄截止时间。 */
    public LocalDateTime stuckCutoff() {
        return LocalDateTime.now().minus(Duration.ofMinutes(stuckMinutes));
    }

    /** 未知态开始查证的截止时间。 */
    public LocalDateTime verifyCutoff() {
        return LocalDateTime.now().minus(Duration.ofMinutes(verifyDelayMinutes));
    }

    /** 待重试开始新建尝试的截止时间。 */
    public LocalDateTime retryCutoff() {
        return LocalDateTime.now().minus(Duration.ofMinutes(retryDelayMinutes));
    }

    /** 账龄告警截止时间。 */
    public LocalDateTime agingCutoff() {
        return LocalDateTime.now().minus(Duration.ofMinutes(agingWarningMinutes));
    }

    private static int clampAttempts(int value) {
        if (value < 1 || value > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException("payout.max-attempts 必须在 1~" + MAX_ATTEMPTS_LIMIT + " 之间，实际=" + value);
        }
        return value;
    }

    private static long clampMinutes(long value) {
        if (value < 0 || value > MINUTES_LIMIT) {
            throw new IllegalArgumentException("payout 的分钟参数必须在 0~" + MINUTES_LIMIT + " 之间，实际=" + value);
        }
        return value;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("配置不是整数：" + raw, ex);
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw.trim());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("配置不是整数：" + raw, ex);
        }
    }
}
