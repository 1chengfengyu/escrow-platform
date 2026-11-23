package com.escrow.platform;

import com.escrow.platform.ai.domain.AiPhase;
import com.escrow.platform.ai.infrastructure.cost.CostDailyStore;
import com.escrow.platform.ai.infrastructure.cost.JdbcCostDailyStore;
import com.escrow.platform.ai.infrastructure.eval.EvalResultRecorder;
import com.escrow.platform.ai.infrastructure.llm.AiCostLedger;
import com.escrow.platform.ai.infrastructure.llm.InMemoryAiCostLedger;
import com.escrow.platform.ai.infrastructure.llm.PersistentAiCostLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V10 两张 AI 汇总表的真库测试：{@code ai_cost_daily} 与 {@code ai_eval_result}。
 *
 * <p>为什么必须真库：这两张表的正确性完全依赖数据库语义——{@code ON DUPLICATE KEY UPDATE}
 * 的累加、唯一键的幂等、CHECK 约束的拦截。用内存替身测，测的是我自己写的假实现。</p>
 *
 * <p><b>不删历史行</b>：{@code ai_cost_daily} 的主键是 {@code (stat_date, phase)}，
 * 删"今天"就是删别的用例/人工验证写下的数据。这里一律用<b>前后差值</b>断言。</p>
 *
 * @since V10
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ESCROW_IT", matches = "true")
class V10AiPersistenceIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AiCostLedger productionLedger;

    private JdbcTemplate jdbcTemplate;
    private CostDailyStore store;
    private String runNo;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        store = new JdbcCostDailyStore(jdbcTemplate);
        runNo = "IT-AI-" + System.nanoTime();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_eval_result WHERE run_no = ?", runNo);
    }

    /** 装配层必须真的用上落库账本，而不是悄悄退回进程内实现。 */
    @Test
    void productionLedgerIsPersistent() {
        assertThat(productionLedger)
                .as("escrow.ai.cost-persist 默认开，且容器里有 JdbcTemplate")
                .isInstanceOf(PersistentAiCostLedger.class);
    }

    /** 两次记账 = 一行累加，不是两行；成本与 token 分别相加。 */
    @Test
    void costLedgerAccumulatesIntoDailyTable() {
        CostDailyStore.DailyUsage before = store.usageOn(LocalDate.now());
        int tokensBefore = before.tokensByPhase().getOrDefault(AiPhase.RERANK, 0);
        long microsBefore = before.costMicros();

        PersistentAiCostLedger ledger = new PersistentAiCostLedger(
                new InMemoryAiCostLedger(100_000), store);
        ledger.record(AiPhase.RERANK, 120, 360L);
        ledger.record(AiPhase.RERANK, 80, 240L);

        CostDailyStore.DailyUsage after = store.usageOn(LocalDate.now());
        assertThat(after.tokensByPhase().get(AiPhase.RERANK) - tokensBefore).isEqualTo(200);
        assertThat(after.costMicros() - microsBefore).isEqualTo(600L);

        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT COUNT(*) FROM ai_cost_daily WHERE stat_date = ? AND phase = 'RERANK'",
                Integer.class, java.sql.Date.valueOf(LocalDate.now()));
        assertThat(rows.get(0)).as("同一 (日期, 阶段) 只有一行").isEqualTo(1);
    }

    /** 新进程从库里恢复基线：另一实例读到的当日用量必须包含刚记的那笔。 */
    @Test
    void freshLedgerSeedsFromPersistedUsage() {
        PersistentAiCostLedger writer = new PersistentAiCostLedger(
                new InMemoryAiCostLedger(100_000), store);
        writer.record(AiPhase.GENERATE, 300, 900L);

        InMemoryAiCostLedger fresh = new InMemoryAiCostLedger(100_000);
        PersistentAiCostLedger restarted = new PersistentAiCostLedger(fresh, store);
        assertThat(restarted.usedTokens(AiPhase.GENERATE)).isGreaterThanOrEqualTo(300);
        assertThat(restarted.costMicrosToday()).isGreaterThanOrEqualTo(900L);
    }

    /** 评测结果按唯一键覆盖：同批次重跑不产生两行互相矛盾的分数。 */
    @Test
    void evalResultUpsertsByUniqueKey() {
        EvalResultRecorder recorder = new EvalResultRecorder(jdbcTemplate);
        recorder.recordJudge(runNo, "reason", "R-001", 3, "judge-v1");
        recorder.recordJudge(runNo, "reason", "R-001", 5, "judge-v1");
        recorder.recordJudgeMean(runNo, "reason", 4.35, "judge-v1");

        List<ScoreRow> rows = jdbcTemplate.query(
                "SELECT metric, metric_bp, judge_score FROM ai_eval_result WHERE run_no = ? AND case_id = 'R-001'",
                (rs, i) -> new ScoreRow(rs.getString("metric"),
                        (Long) rs.getObject("metric_bp"), (Integer) rs.getObject("judge_score")), runNo);
        assertThat(rows).as("唯一键幂等：覆盖而不是追加").hasSize(1);
        assertThat(rows.get(0).judgeScore()).isEqualTo(5);
        assertThat(rows.get(0).metricBp()).isNull();

        Long meanBp = jdbcTemplate.queryForObject(
                "SELECT metric_bp FROM ai_eval_result WHERE run_no = ? AND metric = 'judge_mean'",
                Long.class, runNo);
        assertThat(meanBp).as("均分按万分位整数存").isEqualTo(43500L);
    }

    /** Judge 未评分必须是 NULL 而不是 0：趋势图上"没测"和"测得差"必须可分。 */
    @Test
    void judgeScoreStaysNullWhenSkipped() {
        EvalResultRecorder recorder = new EvalResultRecorder(jdbcTemplate);
        recorder.record(runNo, "ndcg", "Q-001", "ndcg10", 8200L, null, "rank-v1");

        Integer score = jdbcTemplate.queryForObject(
                "SELECT judge_score FROM ai_eval_result WHERE run_no = ? AND case_id = 'Q-001'",
                Integer.class, runNo);
        assertThat(score).isNull();
    }

    /** CHECK 约束之外的值在写入前就被拦下，不靠数据库报错当校验。 */
    @Test
    void judgeScoreOutOfRangeIsRejectedBeforeSql() {
        EvalResultRecorder recorder = new EvalResultRecorder(jdbcTemplate);
        assertThatThrownBy(() -> recorder.recordJudge(runNo, "reason", "R-002", 6, "judge-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record ScoreRow(String metric, Long metricBp, Integer judgeScore) {
    }
}
