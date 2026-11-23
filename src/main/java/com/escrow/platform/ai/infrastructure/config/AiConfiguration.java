package com.escrow.platform.ai.infrastructure.config;

import com.escrow.platform.ai.domain.AnswerComposer;
import com.escrow.platform.ai.domain.CandidateScorer;
import com.escrow.platform.ai.domain.DeviationGuard;
import com.escrow.platform.ai.domain.InjectionGuard;
import com.escrow.platform.ai.domain.KbRetriever;
import com.escrow.platform.ai.domain.LlmGateway;
import com.escrow.platform.ai.domain.ReasonTemplate;
import com.escrow.platform.ai.domain.RuleRerankPolicy;
import com.escrow.platform.ai.domain.SlotParser;
import com.escrow.platform.ai.infrastructure.cost.JdbcCostDailyStore;
import com.escrow.platform.ai.infrastructure.kb.AiKbCorpus;
import com.escrow.platform.ai.infrastructure.llm.AiCostLedger;
import com.escrow.platform.ai.infrastructure.llm.AiQueryCache;
import com.escrow.platform.ai.infrastructure.llm.InMemoryAiCostLedger;
import com.escrow.platform.ai.infrastructure.llm.InMemoryAiQueryCache;
import com.escrow.platform.ai.infrastructure.llm.LlmGatewayRouter;
import com.escrow.platform.ai.infrastructure.llm.OpenAiLlmGateway;
import com.escrow.platform.ai.infrastructure.llm.PersistentAiCostLedger;
import com.escrow.platform.ai.infrastructure.llm.StubLlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * AI 基础设施装配（V10-c1）。
 *
 * <p>模型实现只在这一处二选一：{@code mode=live} 且 key 非空走真实客户端，
 * 否则走桩。业务层不感知这个选择——它们只依赖 {@link LlmGatewayRouter}，
 * 因此"有无 key"不会改变任何业务分支。</p>
 *
 * @since V10
 */
@Slf4j
@Configuration
public class AiConfiguration {

    /** 启动期探测的超时：只用来区分"通"与"不通"，不需要等业务超时那么久。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 成本账本：进程内快检 + {@code ai_cost_daily} 累加。
     *
     * <p>没有 {@link JdbcTemplate}（纯切片测试、或刻意关掉落库）时退回纯进程内实现，
     * 业务代码看不出差别——它只依赖 {@link AiCostLedger} 端口。</p>
     */
    @Bean
    public AiCostLedger aiCostLedger(AiProperties properties, ObjectProvider<JdbcTemplate> jdbcTemplate) {
        InMemoryAiCostLedger local = new InMemoryAiCostLedger(properties.getDailyTokenBudget());
        if (!properties.isCostPersist()) {
            log.info("AI 日成本落库已关闭（escrow.ai.cost-persist=false），只做进程内记账");
            return local;
        }
        JdbcTemplate template = jdbcTemplate.getIfAvailable();
        if (template == null) {
            log.warn("没有可用的 JdbcTemplate，AI 日成本退回纯进程内记账（重启即清零）");
            return local;
        }
        return new PersistentAiCostLedger(local, new JdbcCostDailyStore(template));
    }

    @Bean
    public AiQueryCache aiQueryCache() {
        return new InMemoryAiQueryCache();
    }

    /**
     * 模型客户端：先自检配置，再（可选）探测可达性，最后才决定走真模型还是桩。
     *
     * <p>顺序不能反：探测放在自检之后，才不会拿一个空 URL 去发请求；
     * 而探测本身就是这道修复的重点——默认接入点曾经指向一个本机必然超时的域名，
     * 表现为"功能没坏，但每次检索都慢 10 秒后降级"，这种错误必须在启动期而不是线上被发现。</p>
     */
    @Bean
    public LlmGateway llmGateway(AiProperties properties) {
        properties.validateForLive();
        if (!properties.live()) {
            return new StubLlmGateway();
        }
        if (properties.isStartupProbe()) {
            probeEndpoint(properties.getBaseUrl());
        }
        log.info("AI live 模式：base-url={} model={} fallback={}", properties.getBaseUrl(),
                properties.getModel(), properties.getFallbackModels());
        return new OpenAiLlmGateway(properties.getBaseUrl(), properties.getApiKey(), properties.getModel());
    }

    /**
     * 用一次短超时请求确认接入点可达。
     *
     * <p>任何 HTTP 状态码都算"可达"：401/403 说明域名与 TLS 都通，只是 key 不对，
     * 那是另一类问题；这里只负责把"域名不可达"这种会让你每次等满超时的错误挡在启动期。</p>
     *
     * @throws IllegalStateException 连接不通或超时
     */
    private void probeEndpoint(String baseUrl) {
        String url = baseUrl.replaceAll("/+$", "") + "/models";
        long startedAt = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(PROBE_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .header("Authorization", "Bearer probe")
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("AI 接入点探测通过：{}（HTTP {}，耗时 {} ms）",
                    url, response.statusCode(), System.currentTimeMillis() - startedAt);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("AI 接入点不可达 " + url
                    + "，live 模式启动中止。请检查 ESCROW_AI_BASE_URL 与网络；"
                    + "确需跳过探测可设 escrow.ai.startup-probe=false。原因：" + ex, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 接入点探测被中断 " + url, ex);
        }
    }

    @Bean
    public LlmGatewayRouter llmGatewayRouter(LlmGateway llmGateway, AiCostLedger aiCostLedger,
                                             AiQueryCache aiQueryCache, AiProperties properties) {
        return new LlmGatewayRouter(llmGateway, aiCostLedger, aiQueryCache, properties);
    }

    /** 确定性领域组件：无状态、无外部依赖，集中在这里装配，避免各服务里 new。 */
    @Bean
    public SlotParser slotParser() {
        return new SlotParser();
    }

    @Bean
    public InjectionGuard injectionGuard() {
        return new InjectionGuard();
    }

    @Bean
    public CandidateScorer candidateScorer() {
        return new CandidateScorer();
    }

    @Bean
    public RuleRerankPolicy ruleRerankPolicy() {
        return new RuleRerankPolicy();
    }

    @Bean
    public DeviationGuard deviationGuard() {
        return new DeviationGuard();
    }

    @Bean
    public ReasonTemplate reasonTemplate() {
        return new ReasonTemplate();
    }

    @Bean
    public KbRetriever kbRetriever() {
        return new KbRetriever();
    }

    @Bean
    public AnswerComposer answerComposer() {
        return new AnswerComposer();
    }

    /** 规则语料随包发布；加载失败在启动期暴露，不留到第一次提问。 */
    @Bean
    public AiKbCorpus aiKbCorpus(ObjectMapper objectMapper) throws IOException {
        return AiKbCorpus.load(new ClassPathResource("ai/kb/corpus.jsonl"), objectMapper);
    }
}
