package com.escrow.platform.ai;

import com.escrow.platform.ai.application.AiJudgeService;
import com.escrow.platform.ai.domain.CandidateProfile;
import com.escrow.platform.ai.domain.InjectionGuard;
import com.escrow.platform.ai.domain.ReasonDraft;
import com.escrow.platform.ai.domain.ReasonTemplate;
import com.escrow.platform.ai.domain.SlotParser;
import com.escrow.platform.ai.domain.PromptVersions;
import com.escrow.platform.ai.infrastructure.config.AiProperties;
import com.escrow.platform.ai.infrastructure.eval.EvalResultRecorder;
import com.escrow.platform.ai.infrastructure.llm.InMemoryAiCostLedger;
import com.escrow.platform.ai.infrastructure.llm.InMemoryAiQueryCache;
import com.escrow.platform.ai.infrastructure.llm.LlmGatewayRouter;
import com.escrow.platform.ai.infrastructure.llm.OpenAiLlmGateway;
import com.escrow.platform.ai.support.AiEvalDb;
import com.escrow.platform.ai.support.AiFixtures;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Judge 的 live 回填（V10-c7）：只有配置了 {@code ESCROW_AI_API_KEY} 才执行，
 * 否则整类 skip——默认全量测试不联网、不依赖真实模型。
 *
 * <p>密钥只从环境变量读，绝不落盘。跑完把日志里的均分抄进 {@code docs/ai-engineering.md}，
 * 并注明回填日期与模型名，否则两批数字不可比。</p>
 *
 * @since V10
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "ESCROW_AI_API_KEY", matches = ".+")
class AiLiveEvalTest {

    private static final String CANDIDATE_CORPUS = "ai/candidate-corpus.jsonl";
    private static final String REASON_SET = "ai/reason-set.jsonl";

    private final SlotParser slotParser = new SlotParser();
    private final ReasonTemplate reasonTemplate = new ReasonTemplate();

    /** 用真实模型给 20 条理由打分；任何一条拿不到分数即失败，不允许"部分成功"。 */
    @Test
    void v10c7LiveJudgeScoresAllReasons() {
        AiProperties properties = liveProperties();
        LlmGatewayRouter router = new LlmGatewayRouter(
                new OpenAiLlmGateway(properties.getBaseUrl(), properties.getApiKey(), properties.getModel()),
                new InMemoryAiCostLedger(properties.getDailyTokenBudget()),
                new InMemoryAiQueryCache(), properties);
        AiJudgeService judge = new AiJudgeService(properties, router, new InjectionGuard());
        assertThat(judge.available()).as("live 模式且 key 非空").isTrue();

        Map<Long, CandidateProfile> byId = new LinkedHashMap<>();
        AiFixtures.read(CANDIDATE_CORPUS, AiFixtures.CandidateRow.class)
                .forEach(row -> byId.put(row.userId(), row.toProfile()));

        List<AiFixtures.ReasonCase> cases = AiFixtures.read(REASON_SET, AiFixtures.ReasonCase.class);
        Map<String, Integer> scoreByCase = new LinkedHashMap<>();
        for (int index = 0; index < cases.size(); index++) {
            AiFixtures.ReasonCase item = cases.get(index);
            CandidateProfile profile = byId.get(item.userId());
            assertThat(profile).as("候选人存在 userId=%s", item.userId()).isNotNull();
            ReasonDraft reason = reasonTemplate.compose(profile, slotParser.parse(item.query()));
            // case_id 用固定序号：语料文件顺序即用例身份，换一批语料必须换 run_no
            String caseId = "R-%03d".formatted(index + 1);
            judge.score(profile, slotParser.parse(item.query()), reason.text())
                    .ifPresent(score -> scoreByCase.put(caseId, score));
        }

        List<Integer> scores = new ArrayList<>(scoreByCase.values());
        double mean = scores.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        log.info("[live] Judge 均分 = {}（样本 {} 条，模型 {}，prompt {}）",
                String.format("%.2f", mean), scores.size(), properties.getModel(),
                com.escrow.platform.ai.domain.PromptVersions.JUDGE);
        persist(scoreByCase, mean, properties.getModel());
        assertThat(scores).as("每条都必须拿到 1~5 的分数").hasSize(cases.size());
        assertThat(scores).allMatch(score -> score >= 1 && score <= 5);
    }

    /**
     * 把这一批分数写进 {@code ai_eval_result}。
     *
     * <p>{@code run_no} 里带上模型与 prompt 版本：换模型或换 prompt 之后，
     * 两批数字必须能被区分开，否则"上次是 4.1"这种比对没有任何意义。</p>
     *
     * <p>写库失败只告警，不让评测失败——分数已经打在日志里，评测的结论不该被数据库可用性绑架。</p>
     */
    private void persist(Map<String, Integer> scoreByCase, double mean, String model) {
        EvalResultRecorder recorder = AiEvalDb.recorderOrNull();
        if (recorder == null) {
            log.info("[live] 未配置 DB_HOST，分数只记日志，不写 ai_eval_result");
            return;
        }
        String runNo = "LIVE-" + java.time.LocalDate.now() + "-" + model + "-"
                + PromptVersions.JUDGE;
        try {
            scoreByCase.forEach((caseId, score) ->
                    recorder.recordJudge(runNo, "reason", caseId, score, PromptVersions.JUDGE));
            recorder.recordJudgeMean(runNo, "reason", mean, PromptVersions.JUDGE);
            log.info("[live] 已写入 ai_eval_result：run_no={}，{} 条用例 + 1 条均分", runNo, scoreByCase.size());
        } catch (RuntimeException ex) {
            log.warn("[live] 写 ai_eval_result 失败（不影响评测结论）：{}", ex.getMessage());
        }
    }

    /** live 配置只从环境变量取；base-url 默认 DeepSeek，备模型必须是同供应商模型。 */
    private AiProperties liveProperties() {
        AiProperties properties = new AiProperties();
        properties.setMode("live");
        properties.setApiKey(System.getenv("ESCROW_AI_API_KEY"));
        properties.setBaseUrl(envOr("ESCROW_AI_BASE_URL", "https://api.deepseek.com"));
        properties.setModel(envOr("ESCROW_AI_MODEL", "deepseek-chat"));
        properties.setFallbackModels(envOr("ESCROW_AI_FALLBACK_MODELS", "deepseek-reasoner"));
        return properties;
    }

    private String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
