package com.escrow.platform.ai.infrastructure.llm;

import com.escrow.platform.ai.domain.AiPhase;
import com.escrow.platform.ai.infrastructure.cost.CostDailyStore;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;

/**
 * 落库版成本账本（V10-c1 修复）：进程内快检 + {@code ai_cost_daily} 累加。
 *
 * <p>解决的是上一版留下的两个真实缺口：</p>
 * <ol>
 *   <li><b>重启即清零</b>——进程内计数活不过一次发布，日预算变成"重启就能绕过"；
 *       现在每次记账都原子累加到 {@code ai_cost_daily}，进程启动时再用当天已落库的数量回填。</li>
 *   <li><b>跨实例各算各的</b>——多实例下每个进程都是 200k 预算，实际可用额度被放大；
 *       回填以后各实例从同一个库基线出发，误差只剩"启动后又并发记的那几百毫秒"。</li>
 * </ol>
 *
 * <p><b>写库失败一律不阻断业务</b>：检索是一次用户请求，成本核算是旁路记账，
 * 记账失败不能变成检索失败。连续失败 {@value #MAX_FAILURES} 次后关掉落库，
 * 退回纯进程内记账，并把原因打一次 ERROR——比每 200ms 刷一条 WARN 更有用。</p>
 *
 * <p>不做"先查再改"的累加：并发下会丢更新，累加语义交给数据库唯一键
 * （{@link CostDailyStore#accumulate} 用 {@code ON DUPLICATE KEY UPDATE} 实现）。</p>
 *
 * @since V10
 */
@Slf4j
public class PersistentAiCostLedger implements AiCostLedger {

    /** 连续失败多少次后放弃落库。取 3：偶发断连能自愈，真坏了一眼看出来。 */
    static final int MAX_FAILURES = 3;

    private final InMemoryAiCostLedger delegate;
    private final CostDailyStore store;

    /** 是否已经从库里回填过当天基线。 */
    private boolean seeded;
    /** 落库熔断开关。 */
    private boolean persistEnabled = true;
    private int failures;

    public PersistentAiCostLedger(InMemoryAiCostLedger delegate, CostDailyStore store) {
        this.delegate = delegate;
        this.store = store;
    }

    @Override
    public boolean allow(AiPhase phase, int estimatedTokens) {
        seedOnce();
        return delegate.allow(phase, estimatedTokens);
    }

    @Override
    public void record(AiPhase phase, int tokens, long micros) {
        seedOnce();
        delegate.record(phase, tokens, micros);
        persist(phase, tokens, micros);
    }

    @Override
    public int usedTokens(AiPhase phase) {
        seedOnce();
        return delegate.usedTokens(phase);
    }

    @Override
    public long costMicrosToday() {
        seedOnce();
        return delegate.costMicrosToday();
    }

    /** 当前是否还在落库（测试与运维观测用）。 */
    public boolean persistEnabled() {
        return persistEnabled;
    }

    /**
     * 首次访问时从库回填当天基线。
     *
     * <p>放在第一次 {@code allow/record} 而不是构造器里：构造发生在 Spring 启动期，
     * 那时数据库可能还没就绪（比如容器刚起来），启动失败比"预算基线晚 1 秒"严重得多。</p>
     */
    private void seedOnce() {
        if (seeded) {
            return;
        }
        synchronized (this) {
            if (seeded) {
                return;
            }
            seeded = true;
            try {
                CostDailyStore.DailyUsage usage = store.usageOn(LocalDate.now());
                usage.tokensByPhase().forEach(delegate::seedTokens);
                delegate.seedCostMicros(usage.costMicros());
                if (usage.totalTokens() > 0) {
                    log.info("AI 日成本基线回填：今日已用 {} token，成本 {} micros",
                            usage.totalTokens(), usage.costMicros());
                }
            } catch (RuntimeException ex) {
                // 读不到就当今天从 0 开始：宁可少算，也不能让检索起不来
                log.warn("AI 日成本基线回填失败，按 0 计：{}", ex.getMessage());
            }
        }
    }

    private void persist(AiPhase phase, int tokens, long micros) {
        if (!persistEnabled) {
            return;
        }
        if (phase == AiPhase.FILTER || phase == AiPhase.RECALL) {
            // 纯规则路径不产生费用，也不在 ai_cost_daily 的 CHECK 允许集合里
            return;
        }
        try {
            store.accumulate(LocalDate.now(), phase, tokens, micros);
            failures = 0;
        } catch (RuntimeException ex) {
            failures++;
            if (failures == 1) {
                log.warn("AI 日成本落库失败，业务不受影响，继续重试：{}", ex.getMessage());
            }
            if (failures >= MAX_FAILURES) {
                persistEnabled = false;
                log.error("AI 日成本落库连续失败 {} 次，已降级为纯进程内记账；"
                        + "日预算将只在单进程内有效，重启后归零。请检查 ai_cost_daily 表与数据库连接。", failures);
            }
        }
    }
}
