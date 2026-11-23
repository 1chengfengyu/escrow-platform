package com.escrow.platform.ai.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测指标（V10-c7）：八项口径全部在这里，只有这一份实现。
 *
 * <p>评测脚本不得自带期望值，也不得自算指标——数据集是唯一真相源，
 * 指标算法只有这里一套，否则两次基线数字不可比较。</p>
 *
 * @since V10
 */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    /**
     * 槽位 micro-F1：五个槽位字段各自计 TP/FP/FN 后汇总，避免"空槽位多"虚高。
     *
     * <p>判定规则：期望非空且预测相等 = TP；预测非空但不等 = FP；
     * 期望非空但预测为空或不等 = FN。期望与预测都为空不计入。</p>
     */
    public static double slotF1(List<QuerySlots> predicted, List<QuerySlots> expected) {
        if (predicted.size() != expected.size()) {
            throw new IllegalArgumentException("预测与期望条数必须一致");
        }
        int tp = 0;
        int fp = 0;
        int fn = 0;
        for (int i = 0; i < expected.size(); i++) {
            QuerySlots exp = expected.get(i);
            QuerySlots pred = predicted.get(i);
            Object[][] fields = {
                    {pred.skill(), exp.skill()},
                    {pred.priceMax(), exp.priceMax()},
                    {pred.location(), exp.location()},
                    {pred.weekend(), exp.weekend()},
                    {pred.headcount(), exp.headcount()}
            };
            for (Object[] field : fields) {
                Object p = field[0];
                Object e = field[1];
                if (p == null && e == null) {
                    continue;
                }
                if (e != null && e.equals(p)) {
                    tp++;
                    continue;
                }
                if (p != null) {
                    fp++;
                }
                if (e != null) {
                    fn++;
                }
            }
        }
        return f1(tp, fp, fn);
    }

    private static double f1(int tp, int fp, int fn) {
        if (tp == 0) {
            return 0.0;
        }
        double precision = (double) tp / (tp + fp);
        double recall = (double) tp / (tp + fn);
        return 2 * precision * recall / (precision + recall);
    }

    /**
     * NDCG@k：相关性等级 0/1/2，增益用 {@code 2^rel - 1}，折损用 {@code log2(i+2)}。
     *
     * @param rankedIds 系统返回的候选人顺序
     * @param grades    人工标注：候选人ID → 0/1/2
     */
    public static double ndcgAtK(List<Long> rankedIds, Map<Long, Integer> grades, int k) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(k, rankedIds.size()); i++) {
            int grade = grades.getOrDefault(rankedIds.get(i), 0);
            dcg += gain(grade) / log2(i + 2);
        }
        List<Integer> ideal = new ArrayList<>(grades.values());
        ideal.sort(Comparator.reverseOrder());
        double idcg = 0.0;
        for (int i = 0; i < Math.min(k, ideal.size()); i++) {
            idcg += gain(ideal.get(i)) / log2(i + 2);
        }
        return idcg == 0.0 ? 1.0 : dcg / idcg;
    }

    /** HitRate@k：Top-k 内命中任意一条期望引用即算命中。 */
    public static double hitRateAtK(List<List<String>> retrieved, List<List<String>> expected, int k) {
        if (retrieved.size() != expected.size()) {
            throw new IllegalArgumentException("检索与期望条数必须一致");
        }
        if (retrieved.isEmpty()) {
            return 0.0;
        }
        int hits = 0;
        for (int i = 0; i < retrieved.size(); i++) {
            List<String> top = retrieved.get(i).stream().limit(k).toList();
            if (top.stream().anyMatch(expected.get(i)::contains)) {
                hits++;
            }
        }
        return (double) hits / retrieved.size();
    }

    /** 引用准确率：每条答案的引用里，属于期望引用的比例，再对答案求平均。 */
    public static double citationPrecision(List<AnswerDraft> answers, List<List<String>> expected) {
        if (answers.size() != expected.size()) {
            throw new IllegalArgumentException("答案与期望条数必须一致");
        }
        if (answers.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < answers.size(); i++) {
            AnswerDraft answer = answers.get(i);
            List<String> exp = expected.get(i);
            if (answer.citations().isEmpty()) {
                sum += exp.isEmpty() ? 1.0 : 0.0;
                continue;
            }
            long correct = answer.citations().stream().filter(exp::contains).count();
            sum += (double) correct / answer.citations().size();
        }
        return sum / answers.size();
    }

    /**
     * 幻觉率：未弃答但没有有效引用（引用为空，或答案不是被引片段的原文子串）的比例。
     *
     * <p>弃答不进分母——弃答是"承认不知道"，与"编造"相反。</p>
     */
    public static double hallucinationRate(List<AnswerDraft> answers, Map<String, String> chunkTextById) {
        if (answers.isEmpty()) {
            return 0.0;
        }
        long hallucinated = answers.stream().filter(answer -> !answer.abstain() && !grounded(answer, chunkTextById))
                .count();
        return (double) hallucinated / answers.size();
    }

    private static boolean grounded(AnswerDraft answer, Map<String, String> chunkTextById) {
        if (answer.citations().isEmpty()) {
            return false;
        }
        for (String citation : answer.citations()) {
            String text = chunkTextById.get(citation);
            if (text != null && text.contains(answer.answer())) {
                return true;
            }
        }
        return false;
    }

    /** 可核率：逐条校验通过的占比；任何一条不通过都会把整体拉低于 1.00。 */
    public static double verifiability(List<Boolean> verifiedFlags) {
        if (verifiedFlags.isEmpty()) {
            return 0.0;
        }
        long passed = verifiedFlags.stream().filter(Boolean::booleanValue).count();
        return (double) passed / verifiedFlags.size();
    }

    /** 重排被拒率：被位移守卫丢弃的占比。 */
    public static double rerankRejectedRatio(List<RerankOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return 0.0;
        }
        long rejected = outcomes.stream().filter(RerankOutcome::rejected).count();
        return (double) rejected / outcomes.size();
    }

    /** Judge 均分；空列表返回 0。 */
    public static double meanScore(List<Integer> scores) {
        if (scores.isEmpty()) {
            return 0.0;
        }
        return scores.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private static double gain(int grade) {
        return Math.pow(2, grade) - 1;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    /** 供测试与 trace 使用的等级映射副本（不可变）。 */
    public static Map<Long, Integer> immutableGrades(Map<Long, Integer> grades) {
        return Map.copyOf(new HashMap<>(grades));
    }
}
