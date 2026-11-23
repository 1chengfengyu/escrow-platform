package com.escrow.platform.ai.infrastructure.eval;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code ai_eval_result} 的写入器（V10-c7 修复）。
 *
 * <p>这张表存在的理由很具体：<b>两批数字必须可比</b>。把 Judge 分数只打在日志里，
 * 换一版 prompt 之后就只剩"我记得上次是 4.1"，没法判断是模型变了、语料变了还是 prompt 变了。
 * 落库时把 {@code run_no}、{@code prompt_version}、模型一起写死，任一批次都能回溯到当时的配置。</p>
 *
 * <p>幂等口径与项目其他地方一致：唯一键 {@code (run_no, dataset, case_id, metric)}，
 * 重复跑同一批次是<b>覆盖</b>而不是追加——评测重跑不应产生两行互相矛盾的分数。</p>
 *
 * <p>{@code judge_score} 允许为空：没配 key 时 Judge 是 SKIPPED，不是 0 分。
 * 空与低分在趋势图上必须能区分开。</p>
 *
 * @since V10
 */
@RequiredArgsConstructor
public class EvalResultRecorder {

    /** 单条用例的 Judge 分数指标名。 */
    public static final String METRIC_JUDGE = "judge";

    /** 整批 Judge 均分指标名（万分位整数）。 */
    public static final String METRIC_JUDGE_MEAN = "judge_mean";

    private static final String UPSERT_SQL = """
            INSERT INTO ai_eval_result (id, run_no, dataset, case_id, metric, metric_bp, judge_score, prompt_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE metric_bp = ?, judge_score = ?, prompt_version = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 写一条评测结果。
     *
     * @param runNo         批次号，同一批必须一致
     * @param dataset       数据集名（slot / ndcg / rag / reason）
     * @param caseId        用例 ID
     * @param metric        指标名
     * @param metricBp      指标值，万分位整数；不适用传 null
     * @param judgeScore    Judge 分数 1~5；未评传 null
     * @param promptVersion prompt 版本号
     */
    public void record(String runNo, String dataset, String caseId, String metric,
                       Long metricBp, Integer judgeScore, String promptVersion) {
        if (judgeScore != null && (judgeScore < 1 || judgeScore > 5)) {
            throw new IllegalArgumentException("judgeScore 必须在 1~5，实际 " + judgeScore);
        }
        jdbcTemplate.update(UPSERT_SQL, IdWorker.getId(), runNo, dataset, caseId, metric,
                metricBp, judgeScore, promptVersion, metricBp, judgeScore, promptVersion);
    }

    /** 写一条用例的 Judge 分数。 */
    public void recordJudge(String runNo, String dataset, String caseId, int score, String promptVersion) {
        record(runNo, dataset, caseId, METRIC_JUDGE, null, score, promptVersion);
    }

    /** 写一批的 Judge 均分（{@code mean * 10000} 取整）。 */
    public void recordJudgeMean(String runNo, String dataset, double mean, String promptVersion) {
        record(runNo, dataset, "MEAN", METRIC_JUDGE_MEAN, Math.round(mean * 10_000), null, promptVersion);
    }
}
