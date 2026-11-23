package com.escrow.platform.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步任务链路号单测（V5，NFR-MAIN.04）。
 *
 * @since V5
 */
class TraceContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 任务执行期间 MDC 有 traceId，结束后被清掉。 */
    @Test
    void runWithNewTraceShouldSetAndClearMdc() {
        AtomicReference<String> during = new AtomicReference<>();

        TraceContext.runWithNewTrace("payout-verify", () -> during.set(TraceContextTest.currentTraceId()));

        assertThat(during.get()).startsWith("job-payout-verify-");
        assertThat(during.get()).matches("[0-9a-zA-Z._-]+");
        assertThat(currentTraceId()).isNull();
    }

    /** 嵌套执行结束后还原外层 traceId，不能把外层覆盖掉。 */
    @Test
    void runWithNewTraceShouldRestorePreviousTrace() {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "outer-trace");

        TraceContext.runWithNewTrace("payout-retry", () -> assertThat(currentTraceId())
                .startsWith("job-payout-retry-"));

        assertThat(currentTraceId()).isEqualTo("outer-trace");
    }

    /** 任务异常也要还原 traceId。 */
    @Test
    void runWithNewTraceShouldRestoreOnException() {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "outer-trace");

        try {
            TraceContext.runWithNewTrace("payout-aging", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // 预期异常
        }

        assertThat(currentTraceId()).isEqualTo("outer-trace");
    }

    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
    }
}
