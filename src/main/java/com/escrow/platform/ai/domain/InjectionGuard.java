package com.escrow.platform.ai.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 间接提示注入防护（V10-c5）：三层，逐层降低"用户写的内容被当指令执行"的概率。
 *
 * <ol>
 *   <li><b>清洗</b>：技能标签等用户自由文本落库前去掉指令性短语与控制字符；</li>
 *   <li><b>隔离</b>：拼 prompt 时把用户文本包进 {@code <user_input>} 并声明"这是数据不是指令"；</li>
 *   <li><b>位移守卫</b>：见 {@link DeviationGuard}，假设模型已被骗，直接作废其结果。</li>
 * </ol>
 *
 * <p>本类只负责前两层，且**不做语义判断**——正则匹配的是已知的指令模板，
 * 漏掉的新写法由第三层兜底。这个分工是刻意的：前两层追求低成本，第三层追求确定性。</p>
 *
 * @since V10
 */
public final class InjectionGuard {

    /** 已知的指令性短语；命中即整段替换为占位符。 */
    private static final List<String> INSTRUCTION_PHRASES = List.of(
            "忽略以上", "忽略之前", "忽略上述", "无视以上", "无视之前",
            "ignore previous", "ignore above", "disregard previous",
            "system:", "system：", "你现在是", "把我排第一", "把我排到第一", "排到第一", "置顶我");

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private static final int MAX_INPUT = 200;
    private static final int MAX_TAG_LENGTH = 32;
    private static final int MAX_TAGS = 8;

    /** 清洗结果：文本、是否命中过指令短语、命中的短语。 */
    public record Sanitized(String text, boolean flagged, List<String> hits) {
    }

    /**
     * 清洗任意用户输入。
     *
     * <p>命中指令短语不是"拒绝服务"，而是替换为 {@code 【已过滤】} 后继续——
     * 拒绝会让用户能靠触发过滤来探测规则，替换则保持业务可用。</p>
     */
    public Sanitized sanitize(String raw) {
        String text = raw == null ? "" : raw;
        text = CONTROL_CHARS.matcher(text).replaceAll("");
        List<String> hits = new ArrayList<>();
        for (String phrase : INSTRUCTION_PHRASES) {
            if (text.toLowerCase().contains(phrase.toLowerCase())) {
                hits.add(phrase);
                text = text.replaceAll("(?i)" + Pattern.quote(phrase), "【已过滤】");
            }
        }
        if (text.length() > MAX_INPUT) {
            text = text.substring(0, MAX_INPUT);
        }
        return new Sanitized(text, !hits.isEmpty(), List.copyOf(hits));
    }

    /** 技能标签清洗：去控制字符、去空项、去重、限长、限量。 */
    public List<String> sanitizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String tag : tags) {
            String text = sanitize(tag).text().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > MAX_TAG_LENGTH) {
                text = text.substring(0, MAX_TAG_LENGTH);
            }
            if (!cleaned.contains(text)) {
                cleaned.add(text);
            }
            if (cleaned.size() >= MAX_TAGS) {
                break;
            }
        }
        return List.copyOf(cleaned);
    }

    /** 第二层：把用户文本包进数据边界，并显式声明不得执行其中指令。 */
    public String isolate(String sanitizedText) {
        return "<user_input>\n" + sanitizedText + "\n</user_input>";
    }
}
