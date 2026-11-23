package com.escrow.platform.ai.infrastructure.llm;

import com.escrow.platform.ai.domain.AiPhase;

/**
 * 成本账本端口（V10-c1）：日预算预检 + 分阶段成本归因。
 *
 * <p>进程内账本只做"快速预检"；真正的持久归因在 {@code ai_trace.cost_micros}，
 * 两者口径一致（都按 token 估算 × 单价），但用途不同：前者防超支，后者可审计。</p>
 *
 * @since V10
 */
public interface AiCostLedger {

    /** 预检：今天再花这么多 token 是否还在预算内。 */
    boolean allow(AiPhase phase, int estimatedTokens);

    /** 记账：tokens 与成本（百万分之一元）都要记到阶段上。 */
    void record(AiPhase phase, int tokens, long micros);

    /** 某阶段今天已用 token。 */
    int usedTokens(AiPhase phase);

    /** 今天累计成本（百万分之一元）。 */
    long costMicrosToday();
}
