package com.escrow.platform.ai.support;

import com.escrow.platform.ai.domain.CandidateProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测语料读取（V10-c7）：只读 {@code src/test/resources/ai/} 下的固定 fixture。
 *
 * <p>语料是人工标注的<b>唯一真相源</b>，测试代码只读取、不生成期望值——
 * 否则指标就成了"脚本自己跟自己比"，回归失去意义。</p>
 *
 * @since V10
 */
public final class AiFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiFixtures() {
    }

    /** 按 JSONL 逐行读取。 */
    public static <T> List<T> read(String classpath, Class<T> type) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(classpath).getInputStream(), StandardCharsets.UTF_8))) {
            List<T> items = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    items.add(MAPPER.readValue(line, type));
                }
            }
            return List.copyOf(items);
        } catch (IOException ex) {
            throw new IllegalStateException("读取评测语料失败：" + classpath, ex);
        }
    }

    /** 候选人与画像。 */
    public record CandidateRow(long userId, List<String> skills, Integer experienceYears,
                               Long expectedUnitPrice, String location, Boolean weekendAvailable,
                               Integer completedOrders, Integer ratingX100) {

        /** 画像来源标 FIXTURE：测试语料不得标 DERIVED，避免与真实派生字段混淆。 */
        public CandidateProfile toProfile() {
            if (expectedUnitPrice == null) {
                throw new IllegalStateException("fixture 候选人缺少报价 userId=" + userId);
            }
            return new CandidateProfile(userId, skills, experienceYears == null ? 0 : experienceYears,
                    expectedUnitPrice, location == null ? "" : location,
                    Boolean.TRUE.equals(weekendAvailable),
                    completedOrders == null ? 0 : completedOrders,
                    ratingX100 == null ? 0 : ratingX100);
        }
    }

    /** 查询理解用例：口语 + 人工标注的槽位。 */
    public record QueryCase(String query, ExpectedSlots expected) {
    }

    /** 人工标注槽位；未识别为 {@code null}。 */
    public record ExpectedSlots(String skill, Long priceMax, String location, Boolean weekend,
                                Integer headcount) {
    }

    /** 相关性用例：查询 + 候选人ID → 0/1/2 人工等级。 */
    public record RelevanceCase(String query, Map<String, Integer> grades) {

        /** 转成 userId → 等级。 */
        public Map<Long, Integer> gradeMap() {
            Map<Long, Integer> result = new LinkedHashMap<>();
            grades.forEach((key, value) -> result.put(Long.parseLong(key), value));
            return result;
        }
    }

    /** 问答用例：问题 + 期望引用。 */
    public record QaCase(String question, List<String> expectedCitations) {
    }

    /** 理由用例：查询 + 候选人。 */
    public record ReasonCase(String query, long userId) {
    }
}
