package com.escrow.platform.ai.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 词法检索器（V10-c5）：无 embedding、无向量库时的确定性召回。
 *
 * <p>为什么不用向量：技术栈基线里没有向量数据库，引进来会让"无中间件也要能跑测试"
 * 这条红线失守。评测指标 NDCG / HitRate 衡量的是**本实现**，不是假设的向量检索；
 * 换成向量只影响 `KbRetriever` 的实现，不影响评测口径与阈值。</p>
 *
 * <p>打分规则（整数，可复现）：标题命中 +3、正文命中 +2、问题整句出现在正文 +10。</p>
 *
 * @since V10
 */
public final class KbRetriever {

    private static final int TITLE_HIT = 3;
    private static final int TEXT_HIT = 2;
    private static final int PHRASE_HIT = 10;

    /** 召回 topK 片段；全零分的片段不返回，避免答非所问。 */
    public List<KbChunk> retrieve(String question, List<KbChunk> corpus, int topK) {
        List<Scored> scored = new ArrayList<>();
        for (KbChunk chunk : corpus) {
            int score = score(question, chunk);
            if (score > 0) {
                scored.add(new Scored(chunk, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(scoredChunk -> scoredChunk.chunk().citationId()));
        return scored.stream().limit(Math.max(1, topK)).map(Scored::chunk).toList();
    }

    private int score(String question, KbChunk chunk) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.isEmpty()) {
            return 0;
        }
        int score = chunk.text().contains(normalized) ? PHRASE_HIT : 0;
        for (String token : tokens(normalized)) {
            if (chunk.title().contains(token)) {
                score += TITLE_HIT;
            }
            if (chunk.text().contains(token)) {
                score += TEXT_HIT;
            }
        }
        return score;
    }

    private List<String> tokens(String text) {
        return TextTokenizer.tokenize(text);
    }

    private record Scored(KbChunk chunk, int score) {
    }
}
