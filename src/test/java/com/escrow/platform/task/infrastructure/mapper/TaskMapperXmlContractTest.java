package com.escrow.platform.task.infrastructure.mapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code TaskMapper.xml} 的「文本契约」测试（v2-c3 卡片 §5.3 / §5.4 / §9.2）。
 *
 * <p>只读 XML 文本，不连 MySQL：锁住「命名空间 / 无拼接 / 无 SELECT * / 无 LIMIT /
 * 三个 EXISTS / 静态排序四分支 / 只碰两个表 / 零跨域表 / 七个 if」。</p>
 *
 * @since V2
 */
class TaskMapperXmlContractTest {

    private static final Pattern FROM_TABLE = Pattern.compile("(?i)FROM\\s+([a-z_][a-z0-9_]*)");
    private static final Pattern JOIN_TABLE = Pattern.compile("(?i)JOIN\\s+([a-z_][a-z0-9_]*)");

    private static String xml;

    @BeforeAll
    static void readXml() throws IOException {
        try (InputStream in = TaskMapperXmlContractTest.class.getClassLoader()
                .getResourceAsStream("mapper/task/TaskMapper.xml")) {
            assertThat(in).as("TaskMapper.xml 必须存在于 classpath").isNotNull();
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 命名空间必须是 TaskMapper 全限定名。 */
    @Test
    void namespaceShouldMatchMapperInterface() {
        assertThat(xml).contains(
                "<mapper namespace=\"com.escrow.platform.task.infrastructure.mapper.TaskMapper\">");
    }

    /** 不出现 ${} 字符串拼接。 */
    @Test
    void shouldNotUseDollarPlaceholder() {
        assertThat(xml).doesNotContain("${");
    }

    /** 不出现 SELECT *。 */
    @Test
    void shouldNotSelectStar() {
        String lower = xml.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("select *");
        assertThat(lower).doesNotContain("select  *");
    }

    /** 不写 LIMIT：分页由 PaginationInnerInterceptor 负责。 */
    @Test
    void shouldNotContainLimit() {
        assertThat(xml.toLowerCase(Locale.ROOT)).doesNotContain("limit");
    }

    /** 两个 EXISTS：待裁决名额与剩余名额两个半连接（剩余名额是 COUNT 子查询，不是 EXISTS）。 */
    @Test
    void shouldContainExactlyTwoExistsSubqueries() {
        assertThat(count(xml, "EXISTS (")).isEqualTo(2);
    }

    /** 争议标记在 V2 恒 0，且不 join dispute 表。 */
    @Test
    void shouldHardcodeOpenDisputeAsZero() {
        assertThat(xml).contains("0 AS has_open_dispute");
    }

    /** 四个静态排序分支各有一条 ORDER BY。 */
    @Test
    void shouldContainFourOrderByBranches() {
        assertThat(count(xml, "ORDER BY t.")).isEqualTo(4);
    }

    /** 四个分支都用 id DESC 做稳定兜底（防同值翻页抖动）。 */
    @Test
    void everyOrderByShouldEndWithIdDesc() {
        assertThat(count(xml, "t.id DESC")).isEqualTo(4);
    }

    /** FROM / JOIN 只出现 task 与 task_slot 两个表。 */
    @Test
    void shouldOnlyTouchTaskAndTaskSlotTables() {
        Set<String> tables = new TreeSet<>();
        Matcher from = FROM_TABLE.matcher(xml);
        while (from.find()) {
            tables.add(from.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher join = JOIN_TABLE.matcher(xml);
        while (join.find()) {
            tables.add(join.group(1).toLowerCase(Locale.ROOT));
        }
        assertThat(tables).containsExactly("task", "task_slot");
    }

    /** 零跨域表：两个布尔列别名（列名本身含域词）除外，不出现其它域的表名。 */
    @Test
    void shouldNotReferenceOtherDomains() {
        String withoutAliases = xml
                .replace("has_settlement_abnormal", "")
                .replace("has_open_dispute", "");
        String lower = withoutAliases.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("fund");
        assertThat(lower).doesNotContain("settlement");
        assertThat(lower).doesNotContain("dispute");
        assertThat(lower).doesNotContain("payout");
        assertThat(lower).doesNotContain("join");
    }

    /** 七个动态条件：keyword / category / minPrice / maxPrice / from / to / location。 */
    @Test
    void shouldContainSevenDynamicConditions() {
        assertThat(count(xml, "<if test=\"c.")).isEqualTo(7);
    }

    /** SQL 片段 id 与 resultType 固定。 */
    @Test
    void selectShouldDeclareExpectedIdAndResultType() {
        assertThat(xml).contains("id=\"selectTaskPage\"");
        assertThat(xml).contains(
                "resultType=\"com.escrow.platform.task.infrastructure.mapper.TaskListItemRow\"");
    }

    /** 只读查询：不出现写语句。 */
    @Test
    void shouldBeReadOnly() {
        String upper = xml.toUpperCase(Locale.ROOT);
        assertThat(upper).doesNotContain("INSERT");
        assertThat(upper).doesNotContain("UPDATE ");
        assertThat(upper).doesNotContain("DELETE ");
    }

    // ==================== 辅助 ====================

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
