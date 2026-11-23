package com.escrow.platform.ai.infrastructure.config;

import com.escrow.platform.ai.domain.AiPhase;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code escrow.ai.*} 配置（V10）。
 *
 * <p>默认 {@code mode=stub}：不联网、不需要 key，应用照常启动、全量测试照常绿。
 * 只有显式配 {@code mode=live} 且给了 key，才会构建真实模型客户端。</p>
 *
 * <p><b>gray-ratio 用万分比整数</b>：0 = 全 WEIGHTED，10000 = 全 RERANK，1000 = 10%。
 * 与项目「费率存万分比」的口径一致，避免浮点在配置里漂移。</p>
 *
 * @since V10
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "escrow.ai")
public class AiProperties {

    /**
     * 默认接入点：DeepSeek 的 OpenAI 兼容端点。
     *
     * <p>不用 {@code api.openai.com} 作默认值：那台域名在部分网络环境里是超时的，
     * 一旦把 {@code mode} 改成 live，每一段 stage 都会先等满超时再降级，
     * 表现为"功能没坏但慢得没法用"。默认值必须指向当前实际可用的供应商（Q3 裁决）。</p>
     */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /** 默认模型，与 {@link #DEFAULT_BASE_URL} 同一供应商。 */
    public static final String DEFAULT_MODEL = "deepseek-chat";

    /** stub | live。 */
    private String mode = "stub";

    private String baseUrl = DEFAULT_BASE_URL;

    private String apiKey = "";

    private String model = DEFAULT_MODEL;

    /** 备用模型，逗号分隔；主模型失败时按顺序尝试。 */
    private String fallbackModels = "";

    /** 日 token 预算，跨进程内累加；真正的持久口径看 {@code ai_trace} 汇总。 */
    private int dailyTokenBudget = 200_000;

    private StageTokenBudget stageTokenBudget = new StageTokenBudget();

    private long queryCacheTtlSeconds = 300;

    /**
     * 日成本是否落 {@code ai_cost_daily}（V10-c1 修复）。
     *
     * <p>默认开：进程内账本重启即清零，"预算"会变成一个重启就能绕过的软约束，
     * 落库后既跨重启、也跨实例。写库失败只降级不阻断业务，所以不需要为无库环境单开一条路径。</p>
     */
    private boolean costPersist = true;

    /** live 模式启动时探测 {@code base-url} 是否可达；不通直接启动失败。 */
    private boolean startupProbe = true;

    private Rank rank = new Rank();

    /** 是否走真实模型：mode=live 且 key 非空。 */
    public boolean live() {
        return "live".equalsIgnoreCase(mode) && apiKey != null && !apiKey.isBlank();
    }

    /**
     * live 配置自检：配置错误在启动期暴露，而不是等到第一次检索。
     *
     * <p>三类错误一律快速失败：{@code mode=live} 却没有 key（会静默退化成桩，
     * 让人误以为模型在跑）、{@code base-url} 为空、{@code model} 为空。</p>
     *
     * @throws IllegalStateException 配置不完整
     */
    public void validateForLive() {
        if (!"live".equalsIgnoreCase(mode)) {
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "escrow.ai.mode=live 但 escrow.ai.api-key 为空：要么补 ESCROW_AI_API_KEY，要么改用 mode=stub");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "escrow.ai.mode=live 但 escrow.ai.base-url 为空：请显式配置 ESCROW_AI_BASE_URL");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "escrow.ai.mode=live 但 escrow.ai.model 为空：请显式配置 ESCROW_AI_MODEL");
        }
    }

    /** 主模型 + 备用模型的尝试顺序。 */
    public List<String> modelChain() {
        List<String> chain = new ArrayList<>();
        if (model != null && !model.isBlank()) {
            chain.add(model.trim());
        }
        if (fallbackModels != null && !fallbackModels.isBlank()) {
            for (String candidate : fallbackModels.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty() && !chain.contains(trimmed)) {
                    chain.add(trimmed);
                }
            }
        }
        return chain;
    }

    /** 某阶段的单次预算；未知阶段给通用上限。 */
    public int stageBudget(AiPhase phase) {
        return switch (phase) {
            case UNDERSTAND -> stageTokenBudget.getUnderstand();
            case RERANK -> stageTokenBudget.getRerank();
            case GENERATE -> stageTokenBudget.getGenerate();
            case FILTER, RECALL -> 0;
        };
    }

    /** 阶段预算。 */
    @Getter
    @Setter
    public static class StageTokenBudget {
        private int understand = 1200;
        private int rerank = 1600;
        private int generate = 1200;
    }

    /** 排序与灰度。 */
    @Getter
    @Setter
    public static class Rank {
        private String strategy = "WEIGHTED";
        private int grayRatio = 0;
        private int deviationGuard = 5;
    }
}
