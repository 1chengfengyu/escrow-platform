package com.escrow.platform.ai.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线槽位解析器（V10-c2）：无模型、无网络时的确定性路径。
 *
 * <p>为什么必须有它：验收要求「无 {@code ESCROW_AI_API_KEY} 时全绿」，而槽位理解是模型的活。
 * 解法是把「模型」与「解析器」放在同一个接口后面——没有 key 走本类，有 key 走模型，
 * 两条路径产出同一个 {@link QuerySlots}，评测因此可回归。</p>
 *
 * <p>规则只覆盖语料里出现过的表达，不追求通用；通用性由 live 模式的模型承担。
 * 这是刻意的取舍：**规则解析器的好坏由评测集衡量，而不是由"看起来像 NLP"衡量。**</p>
 *
 * @since V10
 */
public final class SlotParser {

    /** 预算上限：`500 以内` / `500元以下` / `预算 500`。 */
    private static final Pattern PRICE_WITH_SUFFIX = Pattern.compile("(\\d{2,6})\\s*(?:元|块)?\\s*(?:以内|以下|之内|封顶)");
    private static final Pattern PRICE_WITH_PREFIX = Pattern.compile("预算\\s*(?:是|为|在)?\\s*(\\d{2,6})");
    /** 人数：`2 人` / `3 名`。 */
    private static final Pattern HEADCOUNT = Pattern.compile("(\\d{1,2})\\s*(?:个人|人|名)");

    /** 技能别名表：按短语长度从长到短匹配，避免「短视频」吃掉「短视频剪辑」。 */
    private static final Map<String, String> SKILL_ALIASES = skillAliases();

    /** 城市表：评测语料只覆盖这些城市，未命中的城市不会猜。 */
    private static final List<String> CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "苏州", "重庆", "天津");

    /** 停用词：不进入关键词，否则会把「找个」「能干活」当检索词。 */
    private static final List<String> STOP_WORDS = List.of(
            "找个", "找一位", "需要", "要求", "必须", "最好", "能够", "可以", "帮忙", "接单", "干活",
            "报酬", "预算", "以内", "以下", "之内", "封顶",
            "的", "和", "或", "要", "会", "能", "有", "在", "我", "想", "一个", "一位", "请");

    private static final int BASE_CONFIDENCE = 40;

    /**
     * 技能槽未识别时的置信度上限（V12.1 验收修复）。
     *
     * <p>核心意图没解析出来时，即使地点、预算都命中，也不该给出「很懂你」的分数：
     * 找活关键词会退化到零散词，用户看到的 70+ 分与真实理解程度不符。</p>
     */
    private static final int NO_SKILL_CONFIDENCE_CAP = 60;

    /** 纯数字或含数字的碎片（{@code 50} / {@code 00} / {@code 0元}）不进入关键词。 */
    private static final Pattern DIGIT_FRAGMENT = Pattern.compile(".*\\d.*");

    /** 解析一段口语，任何字段识别不出就留 {@code null}，绝不猜测。 */
    public QuerySlots parse(String query) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            return new QuerySlots(null, null, null, null, null, List.of(), 0);
        }
        String skill = matchSkill(text);
        Long priceMax = matchPrice(text);
        String location = matchCity(text);
        Boolean weekend = containsAny(text, "周末", "双休", "周六", "周日") ? Boolean.TRUE : null;
        Integer headcount = matchHeadcount(text);
        List<String> keywords = extractKeywords(text, skill);

        int confidence = BASE_CONFIDENCE;
        if (skill != null) {
            confidence += 15;
        }
        if (priceMax != null) {
            confidence += 10;
        }
        if (location != null) {
            confidence += 10;
        }
        if (Boolean.TRUE.equals(weekend)) {
            confidence += 10;
        }
        if (headcount != null) {
            confidence += 5;
        }
        int bounded = Math.min(95, Math.max(0, confidence));
        if (skill == null) {
            bounded = Math.min(bounded, NO_SKILL_CONFIDENCE_CAP);
        }
        return new QuerySlots(skill, priceMax, location, weekend, headcount, keywords, bounded);
    }

    private String matchSkill(String text) {
        for (Map.Entry<String, String> entry : SKILL_ALIASES.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Long matchPrice(String text) {
        Matcher suffix = PRICE_WITH_SUFFIX.matcher(text);
        if (suffix.find()) {
            return Long.parseLong(suffix.group(1)) * 100L;
        }
        Matcher prefix = PRICE_WITH_PREFIX.matcher(text);
        if (prefix.find()) {
            return Long.parseLong(prefix.group(1)) * 100L;
        }
        return null;
    }

    private String matchCity(String text) {
        for (String city : CITIES) {
            if (text.contains(city)) {
                return city;
            }
        }
        return null;
    }

    private Integer matchHeadcount(String text) {
        Matcher matcher = HEADCOUNT.matcher(text);
        if (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            return value > 0 ? value : null;
        }
        return null;
    }

    /**
     * 提取检索关键词：先把命中的技能与城市、停用词替换成空格，再交给 {@link TextTokenizer}。
     *
     * <p>不先剔除停用词的话，「找个会剪短视频的周末能干活」会被切成包含"找个"的二元组，
     * 关键词全是噪声；替换后剩下的「周末」才是有效信号。</p>
     */
    private List<String> extractKeywords(String text, String skill) {
        String normalized = text;
        if (skill != null) {
            for (Map.Entry<String, String> entry : SKILL_ALIASES.entrySet()) {
                if (entry.getValue().equals(skill)) {
                    normalized = normalized.replace(entry.getKey(), " ");
                }
            }
        }
        // 金额与人数的整段先抽走：否则「500以内」会被二元切成 50/00，
        // 这些数字碎片会污染词法召回（V12.1 验收 P3）。
        normalized = PRICE_WITH_SUFFIX.matcher(normalized).replaceAll(" ");
        normalized = PRICE_WITH_PREFIX.matcher(normalized).replaceAll(" ");
        normalized = HEADCOUNT.matcher(normalized).replaceAll(" ");
        for (String city : CITIES) {
            normalized = normalized.replace(city, " ");
        }
        for (String stopWord : STOP_WORDS) {
            normalized = normalized.replace(stopWord, " ");
        }
        return TextTokenizer.tokenize(normalized).stream()
                .filter(token -> !DIGIT_FRAGMENT.matcher(token).matches())
                .toList();
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 别名按长度降序插入，匹配时长的优先。 */
    private static Map<String, String> skillAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("短视频剪辑", "短视频剪辑");
        aliases.put("剪短视频", "短视频剪辑");
        aliases.put("短视频", "短视频剪辑");
        aliases.put("视频剪辑", "视频剪辑");
        aliases.put("剪辑视频", "视频剪辑");
        aliases.put("剪片", "视频剪辑");
        aliases.put("文案写作", "文案写作");
        aliases.put("写文案", "文案写作");
        aliases.put("文案", "文案写作");
        aliases.put("平面设计", "平面设计");
        aliases.put("设计海报", "平面设计");
        aliases.put("海报", "平面设计");
        aliases.put("编程开发", "编程开发");
        aliases.put("写代码", "编程开发");
        aliases.put("编程", "编程开发");
        aliases.put("数据标注", "数据标注");
        aliases.put("标注", "数据标注");
        // 展会现场类需求：词典里原没有这一族，导致「展会布展」整体掉进关键词（V12.1 验收 P3）
        aliases.put("展会布展", "布展");
        aliases.put("现场布展", "布展");
        aliases.put("布展", "布展");
        aliases.put("会展", "布展");
        aliases.put("展会", "布展");
        aliases.put("客服", "客服");
        aliases.put("拍摄", "拍摄");
        aliases.put("摄影", "拍摄");
        aliases.put("直播", "直播");
        aliases.put("翻译", "翻译");
        return aliases;
    }
}
