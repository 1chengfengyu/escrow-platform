package com.escrow.platform;

import com.escrow.platform.engagement.domain.PerformanceEventType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9 履约记录表结构契约测试（v3-c3，FR-SIGN.START.02 / SRS 4.2.2「履约记录」）。
 *
 * <p>纯单元测试：只读迁移脚本，不连 MySQL，保持「MySQL 未启动时 {@code mvn test} 必须是绿的」。</p>
 *
 * <p>锁死四类改了就炸的事实：表数量（恰好一张）、列集、{@code (contract_id, event_type)} 唯一、
 * 事件白名单与 {@link PerformanceEventType} 四个取值逐字一致。</p>
 *
 * @since V3
 */
class V3PerformanceRecordSchemaContractTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration", "V9__v3_performance_record.sql");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?m)^CREATE TABLE ([a-z_]+) \\($");
    /** 列行 = 缩进 + 小写标识符 + 类型关键字 + 空白；约束 / 索引行天然不匹配。 */
    private static final Pattern COLUMN = Pattern.compile(
            "(?m)^\\s+([a-z_]+)\\s+(?:BIGINT|VARCHAR\\(\\d+\\)|DATETIME\\(3\\))(?=\\s)");

    private static String ddl;
    private static String effectiveDdl;
    private static String tableBlock;

    @BeforeAll
    static void readMigration() throws IOException {
        ddl = Files.readString(MIGRATION);
        effectiveDdl = ddl.replaceAll("(?m)^--.*$", "");
        int start = effectiveDdl.indexOf("CREATE TABLE engagement_performance_record (");
        int end = effectiveDdl.indexOf(") ENGINE=InnoDB", start);
        assertThat(start).as("必须存在 engagement_performance_record 建表块").isGreaterThanOrEqualTo(0);
        assertThat(end).as("建表块必须以 ENGINE=InnoDB 结束").isGreaterThan(0);
        tableBlock = effectiveDdl.substring(start, end);
    }

    /** 迁移必须恰好建一张表：本卡不建交付 / 评价表。 */
    @Test
    void migrationShouldCreateExactlyOneTable() {
        List<String> tables = CREATE_TABLE.matcher(effectiveDdl).results()
                .map(m -> m.group(1)).toList();
        assertThat(tables).containsExactly("engagement_performance_record");
    }

    /** 列集必须与 SRS 4.2.2 的履约记录一致：无更新时间（只追加）、无逻辑删除、无金额。 */
    @Test
    void recordShouldDeclareExactlySixColumns() {
        assertThat(columnsOf(tableBlock)).containsExactlyInAnyOrder(
                "id", "worker_id", "contract_id", "event_type", "occurred_at", "created_at");
    }

    /** 主键是雪花 ID：不得自增。 */
    @Test
    void primaryKeyShouldBeSnowflakeWithoutAutoIncrement() {
        assertThat(tableBlock).contains("PRIMARY KEY (id)");
        assertThat(effectiveDdl).doesNotContain("AUTO_INCREMENT");
    }

    /** 同一签约的同类事件只记一次：唯一键兜底重试与并发。 */
    @Test
    void contractAndEventTypeShouldBeUnique() {
        assertThat(tableBlock)
                .contains("UNIQUE KEY uk_engagement_performance_record_event (contract_id, event_type)");
    }

    /** 事件白名单必须与 PerformanceEventType 的四个取值逐字一致。 */
    @Test
    void eventCheckShouldMatchPerformanceEventType() {
        Matcher matcher = Pattern.compile(
                        "ck_engagement_performance_record_event CHECK \\(event_type IN \\(([^)]*)\\)\\)")
                .matcher(tableBlock);
        assertThat(matcher.find()).as("必须存在事件类型 CHECK 约束").isTrue();
        Set<String> whitelist = Pattern.compile("'([A-Z_]+)'").matcher(matcher.group(1)).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());
        Set<String> expected = Arrays.stream(PerformanceEventType.values())
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(whitelist).containsExactlyInAnyOrderElementsOf(expected);
    }

    /** 按劳动者查履约记录必须有索引；事件时间必须非空。 */
    @Test
    void shouldDeclareIndexesAndNotNullColumns() {
        assertThat(tableBlock).contains(
                "KEY idx_engagement_performance_record_worker_time (worker_id, occurred_at)");
        assertThat(tableBlock).contains("occurred_at DATETIME(3) NOT NULL");
        assertThat(tableBlock).contains("worker_id   BIGINT      NOT NULL");
        assertThat(tableBlock).contains("contract_id BIGINT      NOT NULL");
    }

    /** 本卡只建履约记录：不得出现物理外键、金额小数、种子数据或结构变更语句。 */
    @Test
    void migrationShouldContainNoBannedOrOutOfScopeDdl() {
        assertThat(effectiveDdl)
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES")
                .doesNotContain("DECIMAL")
                .doesNotContain("DOUBLE")
                .doesNotContain("FLOAT")
                .doesNotContain("IF NOT EXISTS")
                .doesNotContain("INSERT INTO")
                .doesNotContain("DROP ")
                .doesNotContain("ALTER ")
                .doesNotContain("deleted")
                .doesNotContain("updated_at");
    }

    private static List<String> columnsOf(String block) {
        return COLUMN.matcher(block).results().map(m -> m.group(1)).toList();
    }
}
