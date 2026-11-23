package com.escrow.platform.ai.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽取式答案合成（V10-c5）。
 *
 * <p>核心约束：<b>答案必须是命中片段的原文子串</b>。做法是从 Top 片段里挑出与问题
 * 重合词最多的那一句直接摘出来，不做改写、不做摘要、不调用模型。这样"引用准确性"
 * 与"幻觉率"都由结构保证，而不是靠 prompt 请求模型自觉。</p>
 *
 * <p>live 模式可以在本类之上加一层模型润色，但润色后的答案仍然要通过
 * "原文子串"校验，通不过就退回本类的抽取结果——这就是"模型可被丢弃"的落点。</p>
 *
 * @since V10
 */
public final class AnswerComposer {

    /** 弃答固定文案：与"编造答案"区分开。 */
    public static final String ABSTAIN_TEXT = "知识库暂未收录该问题，建议联系人工客服";

    /**
     * @param question 用户问题
     * @param chunks   检索命中的片段，按相关度倒序
     */
    public AnswerDraft compose(String question, List<KbChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new AnswerDraft(ABSTAIN_TEXT, List.of(), true);
        }
        KbChunk best = chunks.get(0);
        String sentence = bestSentence(question, best.text());
        if (sentence.isBlank()) {
            return new AnswerDraft(ABSTAIN_TEXT, List.of(), true);
        }
        List<String> citations = new ArrayList<>();
        citations.add(best.citationId());
        for (int i = 1; i < chunks.size(); i++) {
            if (chunks.get(i).text().contains(sentence)) {
                citations.add(chunks.get(i).citationId());
            }
        }
        return new AnswerDraft(sentence, citations, false);
    }

    /** 按句切分，取与问题词重合最多的一句；平票取靠前的。 */
    private String bestSentence(String question, String text) {
        String best = "";
        int bestScore = 0;
        for (String raw : text.split("[。；;]")) {
            String sentence = raw.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            int score = 0;
            for (String token : tokens(question)) {
                if (sentence.contains(token)) {
                    score++;
                }
            }
            if (score > bestScore) {
                best = sentence;
                bestScore = score;
            }
        }
        return best;
    }

    private List<String> tokens(String text) {
        return TextTokenizer.tokenize(text);
    }
}
