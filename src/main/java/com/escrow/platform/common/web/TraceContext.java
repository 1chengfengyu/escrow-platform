package com.escrow.platform.common.web;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 异步任务的链路号工具（V5，NFR-MAIN.04）。
 *
 * <p>HTTP 请求的 traceId 由 {@link TraceIdFilter} 写入 MDC；定时任务线程没有请求上下文，
 * 若不显式设置，任务日志就没有 traceId，出问题无法与业务日志串起来。</p>
 *
 * <p>只做「设置—执行—还原」这一件事：任务结束后恢复原值（通常是 {@code null}），
 * 避免共享线程池把本次 traceId 串到下一次执行。</p>
 *
 * @since V5
 */
public final class TraceContext {

    /** 任务链路号前缀，便于在日志里区分「请求」与「任务」。 */
    public static final String JOB_TRACE_PREFIX = "job-";

    private TraceContext() {
    }

    /**
     * 以新的任务 traceId 执行一段逻辑，结束后还原。
     *
     * @param jobName 任务名，作为 traceId 的可读部分
     * @param action  任务体
     */
    public static void runWithNewTrace(String jobName, Runnable action) {
        String previous = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, newTraceId(jobName));
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
            } else {
                MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, previous);
            }
        }
    }

    /** 生成形如 {@code job-payout-verify-3f2a1b9c} 的 32 位以内的 traceId（与请求头校验规则一致）。 */
    static String newTraceId(String jobName) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String name = jobName == null || jobName.isBlank() ? "task" : jobName;
        return JOB_TRACE_PREFIX + name + "-" + suffix;
    }
}
