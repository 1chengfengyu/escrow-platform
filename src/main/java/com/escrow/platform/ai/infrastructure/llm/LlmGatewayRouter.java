package com.escrow.platform.ai.infrastructure.llm;

import com.escrow.platform.ai.domain.AiPhase;
import com.escrow.platform.ai.domain.AiUnavailableException;
import com.escrow.platform.ai.domain.LlmGateway;
import com.escrow.platform.ai.domain.LlmRequest;
import com.escrow.platform.ai.domain.LlmResult;
import com.escrow.platform.ai.infrastructure.config.AiProperties;

import java.util.List;
import java.util.Optional;

/**
 * 模型网关装饰器（V10-c1）：缓存 → 预算 → 多模型降级 → 成本归因。
 *
 * <p>顺序是有讲究的：<b>先查缓存再查预算</b>，否则命中缓存也会被"预算不足"拒掉，
 * 白白浪费一次可复用的结果。</p>
 *
 * <p>多模型 fallback 只对同一个请求换模型重试，不做语义改写；全部失败抛
 * {@link AiUnavailableException}，由业务层切到确定性路径（降级链）。</p>
 *
 * @since V10
 */
public class LlmGatewayRouter {

    /**
     * 单价：每 1000 token 的成本，单位百万分之一元（≈ ¥1.5/百万 token 的混合估算）。
     *
     * <p><b>它只影响成本展示与告警，不影响预算拦截</b>——{@code allow()} 按 token 计数，
     * 不按钱计数。换供应商后这个值需要重新校准，但校准错了不会让检索被多拦或少拦。</p>
     *
     * <p>业务层写 {@code ai_trace.cost_micros} 与 {@code ai_cost_daily.cost_micros} 时
     * 都用这一份公式，保证三处口径一致；各写一遍迟早会漂移。</p>
     */
    private static final long MICROS_PER_1K_TOKENS = 1500L;

    /**
     * 按 token 估算成本（百万分之一元）。
     *
     * <p>刻意做成静态方法：{@code AiTraceService} 也要用它写 {@code ai_trace.cost_micros}，
     * 让它能直接复用同一份公式，而不是各写一遍再慢慢漂移。</p>
     */
    public static long estimateMicros(int tokens) {
        return tokens <= 0 ? 0L : tokens * MICROS_PER_1K_TOKENS / 1000;
    }

    private final LlmGateway gateway;
    private final AiCostLedger ledger;
    private final AiQueryCache cache;
    private final AiProperties properties;

    public LlmGatewayRouter(LlmGateway gateway, AiCostLedger ledger, AiQueryCache cache, AiProperties properties) {
        this.gateway = gateway;
        this.ledger = ledger;
        this.cache = cache;
        this.properties = properties;
    }

    /** 调用模型；命中缓存时 token 记 0，成本归因仍然落在本次请求的 trace 上。 */
    public LlmResult complete(LlmRequest request) {
        String key = cacheKey(request);
        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            return new LlmResult(cached.get(), "cache", 0, 0, false);
        }

        int estimate = Math.max(1, (request.user().length() + request.system().length()) / 4 + request.maxTokens());
        if (!ledger.allow(request.phase(), estimate)) {
            throw new AiUnavailableException("日 token 预算不足 phase=" + request.phase());
        }

        List<String> chain = request.model() == null || request.model().isBlank()
                ? properties.modelChain()
                : List.of(request.model());
        RuntimeException last = null;
        for (int i = 0; i < chain.size(); i++) {
            LlmRequest attempt = new LlmRequest(request.promptVersion(), request.phase(),
                    request.system(), request.user(), request.maxTokens(), chain.get(i));
            try {
                LlmResult raw = gateway.complete(attempt);
                LlmResult result = new LlmResult(raw.text(), raw.model(), raw.promptTokens(),
                        raw.completionTokens(), i > 0);
                long micros = estimateMicros(result.totalTokens());
                ledger.record(request.phase(), result.totalTokens(), micros);
                cache.put(key, result.text(), properties.getQueryCacheTtlSeconds());
                return result;
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw new AiUnavailableException("全部模型不可用 phase=" + request.phase(), last);
    }

    /** 当前阶段已用 token，供 trace 与测试断言。 */
    public int usedTokens(AiPhase phase) {
        return ledger.usedTokens(phase);
    }

    /** 今日累计成本（百万分之一元）。 */
    public long costMicrosToday() {
        return ledger.costMicrosToday();
    }

    /**
     * 缓存键：版本 + 阶段 + 原文。
     *
     * <p>不做哈希：哈希碰撞会把 A 的结果给 B，而键本身只有几十字节，没必要为省内存引入正确性风险。</p>
     */
    private String cacheKey(LlmRequest request) {
        return request.promptVersion() + '\u0001' + request.phase() + '\u0001' + request.user();
    }
}
