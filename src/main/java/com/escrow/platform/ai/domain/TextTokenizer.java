package com.escrow.platform.ai.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 中文友好的分词器（V10-c5）：离线检索、抽取式答案、槽位关键词共用一份实现。
 *
 * <p>为什么不用空格分词：中文提问整句没有空格，按空格切会得到一个"整句 token"，
 * 在知识库里永远匹配不到，检索会恒为零分。这里对中文段做<b>二元切分</b>——
 * 不依赖词典、可复现、离线可跑，代价是召回偏宽，由打分权重把精度拉回来。</p>
 *
 * <p>ASCII 段（如 {@code KB-PAYOUT-01}）保留整词，不做二元切分，否则会退化成噪声。</p>
 *
 * @since V10
 */
public final class TextTokenizer {

    /** 分隔符：中英文标点与空白。 */
    private static final Pattern SEPARATOR = Pattern.compile("[\\s，,。；;、！!？?：:（）()【】\\[\\]{}\"'“”‘’/|]+");

    /** 纯 ASCII 词（含数字、点、横线、下划线），保留整词。 */
    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z0-9._-]+");

    /** 二元切分的最大段长，超过直接截断，避免超长文本把 token 数打爆。 */
    private static final int MAX_SEGMENT = 32;

    /** 单次切分最多保留的 token 数。 */
    private static final int MAX_TOKENS = 40;

    private TextTokenizer() {
    }

    /**
     * 切词。
     *
     * @param text 任意文本，可为 {@code null}
     * @return 去重后的 token；空文本返回空列表
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        for (String raw : SEPARATOR.split(text)) {
            String segment = raw.trim();
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.length() > MAX_SEGMENT) {
                segment = segment.substring(0, MAX_SEGMENT);
            }
            if (ASCII_WORD.matcher(segment).matches()) {
                add(tokens, segment);
            } else {
                addBigrams(tokens, segment);
            }
            if (tokens.size() >= MAX_TOKENS) {
                break;
            }
        }
        return List.copyOf(tokens);
    }

    /** 中文段：2 字整词 + 全部二元；1 字不成词。 */
    private static void addBigrams(List<String> tokens, String segment) {
        if (segment.length() == 1) {
            return;
        }
        if (segment.length() <= 4) {
            add(tokens, segment);
        }
        for (int i = 0; i + 2 <= segment.length(); i++) {
            add(tokens, segment.substring(i, i + 2));
            if (tokens.size() >= MAX_TOKENS) {
                return;
            }
        }
    }

    private static void add(List<String> tokens, String token) {
        if (token.length() >= 2 && !tokens.contains(token)) {
            tokens.add(token);
        }
    }
}
