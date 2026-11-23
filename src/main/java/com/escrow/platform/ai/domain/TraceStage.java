package com.escrow.platform.ai.domain;

import java.util.List;

/**
 * trace 的一个阶段（V10-c6）：耗时、token、是否降级，以及该阶段内的每次模型调用明细。
 *
 * <p>按 N1 裁决，<b>不建 {@code ai_call_log}</b>：一次请求内的多次模型调用（含重试、fallback、
 * 命中缓存）是这个数组里某个阶段的 {@code calls} 元素。粒度差异靠嵌套表达，
 * trace 回放不需要跨表拼装。</p>
 *
 * @param phase    阶段
 * @param ms       耗时（毫秒）
 * @param tokens   本阶段消耗 token
 * @param degraded 本阶段是否降级到确定性路径
 * @param note     人类可读说明（命中条数、丢弃原因等）
 * @param calls    每次模型调用的明细；确定性阶段为空
 * @since V10
 */
public record TraceStage(AiPhase phase, long ms, int tokens, boolean degraded, String note, List<TraceCall> calls) {

    public TraceStage {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /**
     * 单次模型调用明细。
     *
     * @param model     实际模型；命中缓存时为 {@code cache}
     * @param tokens    token 数；命中缓存为 0
     * @param micros    成本，百万分之一元
     * @param fallback  是否降级到备用模型
     * @param cacheHit  是否命中缓存
     */
    public record TraceCall(String model, int tokens, long micros, boolean fallback, boolean cacheHit) {
    }

    /** 确定性阶段（FILTER / RECALL）：没有 token，只有耗时与说明。 */
    public static TraceStage plain(AiPhase phase, long ms, String note) {
        return new TraceStage(phase, ms, 0, false, note, List.of());
    }
}
