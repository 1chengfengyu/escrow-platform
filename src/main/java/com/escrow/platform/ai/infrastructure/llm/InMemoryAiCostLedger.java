package com.escrow.platform.ai.infrastructure.llm;

import com.escrow.platform.ai.domain.AiPhase;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * 进程内成本账本（V10-c1）：按阶段累加 token 与成本，跨天自动清零。
 *
 * <p>为什么不用 Redis：预算预检是"防超支"的软约束，允许跨实例各自计数；
 * 真正的可审计数字落在 {@code ai_trace}，不依赖本类。引入 Redis 会让
 * 无中间件的测试变红，收益不抵成本。</p>
 *
 * <p>本类<b>不落库</b>：需要跨重启、跨实例的日汇总时，由
 * {@link PersistentAiCostLedger} 在外面套一层写 {@code ai_cost_daily}，
 * 本类只保留"快"的那一半语义。</p>
 *
 * @since V10
 */
public class InMemoryAiCostLedger implements AiCostLedger {

    private final int dailyBudget;
    private final Map<AiPhase, Integer> tokensByPhase = new EnumMap<>(AiPhase.class);
    private LocalDate day = LocalDate.now();
    private long micros;

    public InMemoryAiCostLedger(int dailyBudget) {
        this.dailyBudget = dailyBudget;
    }

    @Override
    public synchronized boolean allow(AiPhase phase, int estimatedTokens) {
        rollIfNewDay();
        int used = totalTokens();
        return used + Math.max(0, estimatedTokens) <= dailyBudget;
    }

    @Override
    public synchronized void record(AiPhase phase, int tokens, long micros) {
        rollIfNewDay();
        tokensByPhase.merge(phase, Math.max(0, tokens), Integer::sum);
        this.micros += Math.max(0, micros);
    }

    @Override
    public synchronized int usedTokens(AiPhase phase) {
        rollIfNewDay();
        return tokensByPhase.getOrDefault(phase, 0);
    }

    @Override
    public synchronized long costMicrosToday() {
        rollIfNewDay();
        return micros;
    }

    /**
     * 用当天已落库的用量回填某阶段的进程内计数（启动/首次访问时一次性调用）。
     *
     * <p>只<b>覆盖</b>不累加：回填发生在本次进程还没有任何记账之前，
     * 若做成累加，重复回填会把预算双算。</p>
     */
    public synchronized void seedTokens(AiPhase phase, int tokens) {
        rollIfNewDay();
        tokensByPhase.put(phase, Math.max(0, tokens));
    }

    /** 回填当天已落库的累计成本（百万分之一元），语义同 {@link #seedTokens}。 */
    public synchronized void seedCostMicros(long micros) {
        rollIfNewDay();
        this.micros = Math.max(0, micros);
    }

    private int totalTokens() {
        return tokensByPhase.values().stream().mapToInt(Integer::intValue).sum();
    }

    private void rollIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            day = today;
            tokensByPhase.clear();
            micros = 0L;
        }
    }
}
