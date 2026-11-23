package com.escrow.platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7 报名表结构契约测试（v3-c1，FR-SIGN.APPLY.06）。
 *
 * <p>纯单元测试：只读迁移脚本与 SRS 文本，不连 MySQL，保持
 * 「MySQL 未启动时 {@code mvn test} 必须是绿的」这条纪律。</p>
 *
 * <p>锁死四类「改了就炸」的事实：表数量、列集、有效报名唯一索引的生成列口径、
 * 状态白名单与 SRS A.3.7 逐字一致。</p>
 *
 * @since V3
 */
class V3ApplicationSchemaContractTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration", "V7__v3_engagement_application.sql");

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
        int start = effectiveDdl.indexOf("CREATE TABLE engagement_application (");
        int end = effectiveDdl.indexOf(") ENGINE=InnoDB", start);
        assertThat(start).as("必须存在 engagement_application 建表块").isGreaterThanOrEqualTo(0);
        assertThat(end).as("建表块必须以 ENGINE=InnoDB 结束").isGreaterThan(start);
        tableBlock = effectiveDdl.substring(start, end);
    }

    /** 迁移必须恰好建一张表：本卡不提前建签约 / 交付表。 */
    @Test
    void migrationShouldCreateExactlyOneTable() {
        List<String> tables = CREATE_TABLE.matcher(effectiveDdl).results()
                .map(m -> m.group(1)).toList();
        assertThat(tables).containsExactly("engagement_application");
    }

    /** 列集必须与 SRS 4.2.2 D3 的报名属性一致（无多无少）。 */
    @Test
    void applicationShouldDeclareExactlyTenColumns() {
        assertThat(columnsOf(tableBlock)).containsExactlyInAnyOrder(
                "id", "task_id", "worker_id", "status", "applied_at", "withdrawn_at",
                "active_task_id", "active_worker_id", "created_at", "updated_at");
    }

    /** 有效报名唯一性必须落在「生成列 + 唯一索引」上（MySQL 无部分唯一索引）。 */
    @Test
    void activeApplicationShouldBeUniqueByGeneratedColumns() {
        assertThat(tableBlock).contains(
                "UNIQUE KEY uk_engagement_application_active (active_task_id, active_worker_id)");
        assertThat(tableBlock).contains(
                "active_task_id   BIGINT GENERATED ALWAYS AS "
                        + "(CASE WHEN status IN ('APPLY_PENDING','APPLY_HIRED') THEN task_id END) VIRTUAL");
        assertThat(tableBlock).contains(
                "active_worker_id BIGINT GENERATED ALWAYS AS "
                        + "(CASE WHEN status IN ('APPLY_PENDING','APPLY_HIRED') THEN worker_id END) VIRTUAL");
        assertThat(count(tableBlock, "GENERATED ALWAYS AS")).isEqualTo(2);
    }

    /** 状态白名单必须与 FR-SIGN.APPLY.06 / A.3.7 的四个取值逐字一致。 */
    @Test
    void statusCheckShouldMatchSrsFourStates() {
        Matcher matcher = Pattern.compile("ck_engagement_application_status CHECK \\(status IN \\(([^)]*)\\)\\)")
                .matcher(tableBlock);
        assertThat(matcher.find()).as("必须存在状态 CHECK 约束").isTrue();
        Set<String> whitelist = Pattern.compile("'([A-Z_]+)'").matcher(matcher.group(1)).results()
                .map(m -> m.group(1)).collect(java.util.stream.Collectors.toSet());
        assertThat(whitelist).containsExactlyInAnyOrder(
                "APPLY_PENDING", "APPLY_HIRED", "APPLY_REJECTED", "APPLY_WITHDRAWN");
    }

    /** 两个查询入口必须有索引：按任务倒序、按劳动者倒序。 */
    @Test
    void shouldDeclareTaskAndWorkerTimeIndexes() {
        assertThat(tableBlock).contains(
                "KEY idx_engagement_application_task_time (task_id, applied_at)");
        assertThat(tableBlock).contains(
                "KEY idx_engagement_application_worker_time (worker_id, applied_at)");
    }

    /** 报名时间由库填默认值，应用层不传（避免时区与时钟漂移）。 */
    @Test
    void appliedAtShouldDefaultToDatabaseClock() {
        assertThat(tableBlock).contains("applied_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");
    }

    /** 本卡不占名额：迁移不得写 task_slot，也不得出现资金 / 物理外键 / 自增。 */
    @Test
    void migrationShouldContainNoBannedOrOutOfScopeDdl() {
        assertThat(effectiveDdl)
                .doesNotContain("task_slot")
                .doesNotContain("AUTO_INCREMENT")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES")
                .doesNotContain("DECIMAL")
                .doesNotContain("DOUBLE")
                .doesNotContain("FLOAT")
                .doesNotContain("IF NOT EXISTS")
                .doesNotContain("INSERT INTO")
                .doesNotContain("DROP ")
                .doesNotContain("ALTER ");
    }

    private static List<String> columnsOf(String block) {
        return COLUMN.matcher(block).results().map(m -> m.group(1)).toList();
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
