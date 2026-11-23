package com.escrow.platform.ai;

import com.escrow.platform.ai.domain.AnswerComposer;
import com.escrow.platform.ai.domain.AnswerDraft;
import com.escrow.platform.ai.domain.CandidateFilter;
import com.escrow.platform.ai.domain.CandidateProfile;
import com.escrow.platform.ai.domain.CandidateScorer;
import com.escrow.platform.ai.domain.DeviationGuard;
import com.escrow.platform.ai.domain.KbChunk;
import com.escrow.platform.ai.domain.KbRetriever;
import com.escrow.platform.ai.domain.MetricsCalculator;
import com.escrow.platform.ai.domain.QuerySlots;
import com.escrow.platform.ai.domain.ReasonDraft;
import com.escrow.platform.ai.domain.ReasonTemplate;
import com.escrow.platform.ai.domain.RerankOutcome;
import com.escrow.platform.ai.domain.ScoredCandidate;
import com.escrow.platform.ai.domain.SlotParser;
import com.escrow.platform.ai.infrastructure.config.AiProperties;
import com.escrow.platform.ai.infrastructure.kb.AiKbCorpus;
import com.escrow.platform.ai.infrastructure.llm.InMemoryAiCostLedger;
import com.escrow.platform.ai.infrastructure.llm.InMemoryAiQueryCache;
import com.escrow.platform.ai.infrastructure.llm.LlmGatewayRouter;
import com.escrow.platform.ai.infrastructure.llm.StubLlmGateway;
import com.escrow.platform.ai.support.AiFixtures;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 评测基线（c7）：八项指标的实际数字由这份测试产出，{@code docs/ai-engineering.md} 抄的就是它。
 *
 * <p>无 key 时全绿：槽位走规则解析、排序走确定性打分、答案走抽取式合成，
 * 唯一需要模型的 Judge 记为 SKIPPED，而不是 0 分。</p>
 *
 * @since V10
 */
@Slf4j
class AiEvalTest {

    private static final String QUERY_SET = "ai/query-set.jsonl";
    private static final String RELEVANCE_SET = "ai/relevance-set.jsonl";
    private static final String QA_SET = "ai/rule-qa-set.jsonl";
    private static final String REASON_SET = "ai/reason-set.jsonl";
    private static final String CANDIDATE_CORPUS = "ai/candidate-corpus.jsonl";
    private static final String KB_CORPUS = "ai/kb/corpus.jsonl";

    private final SlotParser slotParser = new SlotParser();
    private final CandidateScorer scorer = new CandidateScorer();
    private final ReasonTemplate reasonTemplate = new ReasonTemplate();
    private final KbRetriever kbRetriever = new KbRetriever();
    private final AnswerComposer answerComposer = new AnswerComposer();
    private final DeviationGuard deviationGuard = new DeviationGuard();

    /** 查询理解槽位 F1，目标 ≥ 0.80。 */
    @Test
    void v10c2SlotF1MeetsBaseline() {
        List<AiFixtures.QueryCase> cases = AiFixtures.read(QUERY_SET, AiFixtures.QueryCase.class);
        assertThat(cases).hasSize(30);

        List<QuerySlots> predicted = new ArrayList<>();
        List<QuerySlots> expected = new ArrayList<>();
        for (AiFixtures.QueryCase item : cases) {
            AiFixtures.ExpectedSlots exp = item.expected();
            predicted.add(slotParser.parse(item.query()));
            expected.add(new QuerySlots(exp.skill(), exp.priceMax(), exp.location(), exp.weekend(),
                    exp.headcount(), List.of(), 0));
        }
        double f1 = MetricsCalculator.slotF1(predicted, expected);
        log.info("[baseline] 槽位 F1 = {}", String.format("%.4f", f1));
        assertThat(f1).isGreaterThanOrEqualTo(0.80);
    }

    /** 检索 NDCG@5，目标 ≥ 0.75。 */
    @Test
    void v10c4NdcgAt5MeetsBaseline() {
        List<CandidateProfile> candidates = candidates();
        List<AiFixtures.RelevanceCase> cases = AiFixtures.read(RELEVANCE_SET, AiFixtures.RelevanceCase.class);
        assertThat(cases).hasSize(20);

        double sum = 0.0;
        for (AiFixtures.RelevanceCase item : cases) {
            QuerySlots slots = slotParser.parse(item.query());
            CandidateFilter filter = CandidateFilter.from(slots);
            List<CandidateProfile> passed = candidates.stream().filter(filter::matches).toList();
            List<Long> ranked = scorer.score(slots, passed).stream()
                    .map(candidate -> candidate.profile().userId())
                    .toList();
            double ndcg = MetricsCalculator.ndcgAtK(ranked, item.gradeMap(), 5);
            log.info("[baseline] NDCG@5 query={} ranked={} ndcg={}", item.query(), ranked,
                    String.format("%.4f", ndcg));
            sum += ndcg;
        }
        double average = sum / cases.size();
        log.info("[baseline] NDCG@5 均值 = {}", String.format("%.4f", average));
        assertThat(average).isGreaterThanOrEqualTo(0.75);
    }

    /** RAG：HitRate@3 ≥ 0.85、引用准确率 ≥ 0.90、幻觉率 ≤ 0.05。 */
    @Test
    void v10c5RagMetricsMeetBaseline() throws IOException {
        List<KbChunk> corpus = AiKbCorpus.load(new ClassPathResource(KB_CORPUS), new com.fasterxml.jackson.databind.ObjectMapper())
                .chunks();
        Map<String, String> chunkTextById = new LinkedHashMap<>();
        corpus.forEach(chunk -> chunkTextById.put(chunk.citationId(), chunk.text()));

        List<AiFixtures.QaCase> cases = AiFixtures.read(QA_SET, AiFixtures.QaCase.class);
        assertThat(cases).hasSize(20);
        List<List<String>> retrieved = new ArrayList<>();
        List<List<String>> expected = new ArrayList<>();
        List<AnswerDraft> answers = new ArrayList<>();
        for (AiFixtures.QaCase item : cases) {
            List<KbChunk> hits = kbRetriever.retrieve(item.question(), corpus, 3);
            List<String> ids = hits.stream().map(KbChunk::citationId).toList();
            retrieved.add(ids);
            expected.add(item.expectedCitations());
            answers.add(answerComposer.compose(item.question(), hits));
            if (ids.isEmpty() || !ids.contains(item.expectedCitations().get(0))) {
                log.warn("[baseline] 检索未命中期望引用 question={} retrieved={}", item.question(), ids);
            }
        }

        double hitRate = MetricsCalculator.hitRateAtK(retrieved, expected, 3);
        double citationPrecision = MetricsCalculator.citationPrecision(answers, expected);
        double hallucination = MetricsCalculator.hallucinationRate(answers, chunkTextById);
        log.info("[baseline] HitRate@3 = {}，引用准确率 = {}，幻觉率 = {}",
                String.format("%.4f", hitRate), String.format("%.4f", citationPrecision),
                String.format("%.4f", hallucination));
        assertThat(hitRate).isGreaterThanOrEqualTo(0.85);
        assertThat(citationPrecision).isGreaterThanOrEqualTo(0.90);
        assertThat(hallucination).isLessThanOrEqualTo(0.05);
    }

    /** 理由可核率必须为 1.00：任何一条不可核即失败。 */
    @Test
    void v10c7ReasonVerifiabilityIsOne() {
        Map<Long, CandidateProfile> byId = new LinkedHashMap<>();
        candidates().forEach(candidate -> byId.put(candidate.userId(), candidate));
        List<AiFixtures.ReasonCase> cases = AiFixtures.read(REASON_SET, AiFixtures.ReasonCase.class);
        assertThat(cases).hasSize(20);

        List<Boolean> verified = new ArrayList<>();
        for (AiFixtures.ReasonCase item : cases) {
            CandidateProfile profile = byId.get(item.userId());
            assertThat(profile).as("候选人存在 userId=%s", item.userId()).isNotNull();
            ReasonDraft reason = reasonTemplate.compose(profile, slotParser.parse(item.query()));
            assertThat(reason.segments()).isNotEmpty();
            boolean ok = reasonTemplate.verify(profile, reason);
            if (!ok) {
                log.error("[baseline] 理由不可核 userId={} reason={}", item.userId(), reason.text());
            }
            verified.add(ok);
        }
        double rate = MetricsCalculator.verifiability(verified);
        log.info("[baseline] 理由可核率 = {}", String.format("%.2f", rate));
        assertThat(rate).isEqualTo(1.00);
    }

    /** 重排被拒率：位移超过守卫时整次作废，指标口径可计算。 */
    @Test
    void v10c7RerankRejectedRatio() {
        List<ScoredCandidate> base = scorer.score(new QuerySlots("短视频剪辑", null, null, null, null,
                List.of(), 80), candidates());
        List<ScoredCandidate> inverted = new ArrayList<>(base);
        java.util.Collections.reverse(inverted);

        RerankOutcome rejected = deviationGuard.enforce(base, inverted, 5);
        RerankOutcome accepted = deviationGuard.enforce(base, base, 5);
        double ratio = MetricsCalculator.rerankRejectedRatio(List.of(rejected, accepted, rejected, accepted));
        log.info("[baseline] 重排被拒率（示例 2/4） = {}", String.format("%.2f", ratio));

        assertThat(rejected.rejected()).isTrue();
        assertThat(rejected.ranked()).isEqualTo(base);
        assertThat(accepted.rejected()).isFalse();
        assertThat(ratio).isEqualTo(0.5);
    }

    /**
     * Judge：无 key 时必须 SKIPPED（空结果），不能伪造分数、也不能因为缺 key 让测试失败。
     */
    @Test
    void v10c7JudgeSkippedWithoutApiKey() {
        AiProperties properties = new AiProperties();
        LlmGatewayRouter router = new LlmGatewayRouter(new StubLlmGateway(),
                new InMemoryAiCostLedger(properties.getDailyTokenBudget()), new InMemoryAiQueryCache(), properties);
        com.escrow.platform.ai.application.AiJudgeService judge =
                new com.escrow.platform.ai.application.AiJudgeService(properties, router,
                        new com.escrow.platform.ai.domain.InjectionGuard());

        assertThat(judge.available()).isFalse();
        assertThat(judge.score(candidates().get(0), slotParser.parse("找个会剪短视频的"), "技能匹配")).isEmpty();
        log.info("[baseline] LLM-as-Judge = SKIPPED（未配置 ESCROW_AI_API_KEY）");
    }

    private List<CandidateProfile> candidates() {
        return AiFixtures.read(CANDIDATE_CORPUS, AiFixtures.CandidateRow.class).stream()
                .map(AiFixtures.CandidateRow::toProfile)
                .toList();
    }
}
