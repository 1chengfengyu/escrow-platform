package com.escrow.platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 任务检索索引迁移的「文本契约」测试（v2-c3 卡片 §5.2 / §9.2）。
 *
 * <p>只读 SQL 文本，不连 MySQL：锁住「两条索引、列序、只加索引、版本最大、引用 #48」。
 * 真正的执行验证在探针与 EXPLAIN 里，不在单测里。</p>
 *
 * @since V2
 */
class V2TaskSearchIndexContractTest {

    private static final String SCRIPT_NAME = "db/migration/V6__v2_task_search_index.sql";
    private static final Pattern ADD_KEY =
            Pattern.compile("(?i)ADD\\s+KEY\\s+(\\w+)\\s*\\(([^)]+)\\)");
    private static final Pattern VERSION =
            Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static String script;

    @BeforeAll
    static void readScript() throws IOException {
        try (InputStream in = V2TaskSearchIndexContractTest.class.getClassLoader()
                .getResourceAsStream(SCRIPT_NAME)) {
            assertThat(in).as("V6 迁移必须存在于 classpath：%s", SCRIPT_NAME).isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 脚本非空。 */
    @Test
    void scriptShouldNotBeEmpty() {
        assertThat(script.trim()).isNotEmpty();
    }

    /** 恰好两条 ADD KEY。 */
    @Test
    void shouldAddExactlyTwoIndexes() {
        Matcher matcher = ADD_KEY.matcher(script);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        assertThat(count).isEqualTo(2);
    }

    /** idx_task_list_published 的列序固定为 (recruiting_closed, published_at, id)。 */
    @Test
    void publishedIndexShouldHaveExpectedColumns() {
        assertThat(columnsOf("idx_task_list_published"))
                .containsExactly("recruiting_closed", "published_at", "id");
    }

    /** idx_task_list_price 的列序固定为 (recruiting_closed, unit_price, id)。 */
    @Test
    void priceIndexShouldHaveExpectedColumns() {
        assertThat(columnsOf("idx_task_list_price"))
                .containsExactly("recruiting_closed", "unit_price", "id");
    }

    /** 只加索引：不删索引、不改列、不建表、不加唯一约束、不做全文索引。 */
    @Test
    void scriptShouldOnlyAddIndexes() {
        String upper = script.toUpperCase(Locale.ROOT);
        assertThat(upper).doesNotContain("DROP");
        assertThat(upper).doesNotContain("ALTER COLUMN");
        assertThat(upper).doesNotContain("CREATE TABLE");
        assertThat(upper).doesNotContain("UNIQUE");
        assertThat(upper).doesNotContain("FULLTEXT");
    }

    /** 注释里引用决策记录 #48，便于追溯。 */
    @Test
    void scriptShouldReferenceDecision48() {
        assertThat(script).contains("docs/decisions.md #48");
    }

    /** V6 是 V2 批次的封口版本；V3 起允许更高版本，但版本号必须唯一且不回填到 6 以内。 */
    @Test
    void v6ShouldRemainTheLatestV2Migration() throws IOException {
        Path dir = Path.of("src/main/resources/db/migration");
        assertThat(Files.isDirectory(dir)).as("迁移目录必须存在：%s", dir).isTrue();

        List<Integer> versions;
        try (Stream<Path> files = Files.list(dir)) {
            versions = files.map(path -> VERSION.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();
        }
        assertThat(versions).as("V6 必须仍存在").contains(6);
        assertThat(versions).as("迁移版本号不得重复").doesNotHaveDuplicates();
        assertThat(versions.get(versions.size() - 1)).as("后续版本只能向后递增")
                .isGreaterThanOrEqualTo(6);
    }

    /** 只 ALTER 一次 task 表，不动别的表。 */
    @Test
    void scriptShouldOnlyAlterTaskTable() {
        assertThat(countIgnoreCase(script, "ALTER TABLE")).isEqualTo(1);
        assertThat(countIgnoreCase(script, "ALTER TABLE task")).isEqualTo(1);
    }

    /** 不灌种子数据。 */
    @Test
    void scriptShouldNotInsertData() {
        assertThat(script.toUpperCase(Locale.ROOT)).doesNotContain("INSERT");
    }

    // ==================== 辅助 ====================

    private static List<String> columnsOf(String indexName) {
        Matcher matcher = ADD_KEY.matcher(script);
        while (matcher.find()) {
            if (matcher.group(1).equals(indexName)) {
                return Arrays.stream(matcher.group(2).split(","))
                        .map(String::trim)
                        .toList();
            }
        }
        return List.of();
    }

    private static int countIgnoreCase(String haystack, String needle) {
        String lowerHaystack = haystack.toLowerCase(Locale.ROOT);
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        int count = 0;
        int index = 0;
        while ((index = lowerHaystack.indexOf(lowerNeedle, index)) >= 0) {
            count++;
            index += lowerNeedle.length();
        }
        return count;
    }
}
