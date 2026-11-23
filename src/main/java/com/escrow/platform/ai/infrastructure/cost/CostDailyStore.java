package com.escrow.platform.ai.infrastructure.cost;

import com.escrow.platform.ai.domain.AiPhase;

import java.time.LocalDate;
import java.util.Map;

/**
 * 日成本汇总的持久化端口（V10-c1 修复）。
 *
 * <p>只管两件事：<b>原子累加</b>（{@code ON DUPLICATE KEY UPDATE}），
 * 与<b>按天读回</b>（进程重启后恢复预算基线）。刻意不做"先查再改"：
 * 多实例并发记账时读-改-写会丢更新，累加必须交给数据库的唯一键做。</p>
 *
 * <p>只统计模型阶段（UNDERSTAND / RERANK / GENERATE），与 {@code ai_cost_daily}
 * 的 CHECK 约束一致；FILTER / RECALL 是纯规则路径，不产生费用。</p>
 *
 * @since V10
 */
public interface CostDailyStore {

    /**
     * 原子累加某天某阶段的用量。
     *
     * @param date       统计日期
     * @param phase      模型阶段
     * @param tokens     token 数（负数按 0 处理）
     * @param costMicros 成本，百万分之一元（负数按 0 处理）
     */
    void accumulate(LocalDate date, AiPhase phase, int tokens, long costMicros);

    /**
     * 读取某天的累计用量。
     *
     * @param date 统计日期
     * @return 用量快照；当天没有记录时返回空快照
     */
    DailyUsage usageOn(LocalDate date);

    /**
     * 一天的用量快照。
     *
     * @param tokensByPhase 阶段 → token 数
     * @param costMicros    当天累计成本，百万分之一元
     */
    record DailyUsage(Map<AiPhase, Integer> tokensByPhase, long costMicros) {

        /** 空快照：用于"当天还没有记账"与读库失败两种情形。 */
        public static DailyUsage empty() {
            return new DailyUsage(Map.of(), 0L);
        }

        /** 当天总 token。 */
        public int totalTokens() {
            return tokensByPhase.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
