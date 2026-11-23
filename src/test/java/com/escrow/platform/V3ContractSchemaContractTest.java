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
 * V8 签约表结构契约测试（v3-c2，FR-SIGN.HIRE.01 / SRS 4.2.2 / A.3.1）。
 *
 * <p>纯单元测试：只读迁移脚本，不连 MySQL，保持「MySQL 未启动时 {@code mvn test} 必须是绿的」。</p>
 *
 * <p>锁死四类改了就炸的事实：表数量（恰好一张）、列集、<b>有效签约唯一</b>的生成列口径、
 * 状态白名单与 {@code ContractStatus} 五个取值逐字一致。</p>
 *
 * @since V3
 */
class V3ContractSchemaContractTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration", "V8__v3_engagement_contract.sql");

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
        int start = effectiveDdl.indexOf("CREATE TABLE engagement_contract (");
        int end = effectiveDdl.indexOf(") ENGINE=InnoDB", start);
        assertThat(start).as("必须存在 engagement_contract 建表块").isGreaterThanOrEqualTo(0);
        assertThat(end).as("建表块必须以 ENGINE=InnoDB 结束").isGreaterThan(0);
        tableBlock = effectiveDdl.substring(start, end);
    }

    /** 迁移必须恰好建一张表：本卡不建交付 / 结算表。 */
    @Test
    void migrationShouldCreateExactlyOneTable() {
        List<String> tables = CREATE_TABLE.matcher(effectiveDdl).results()
                .map(m -> m.group(1)).toList();
        assertThat(tables).containsExactly("engagement_contract");
    }

    /** 列集必须与 SRS 4.2.2 的签约属性一致：无任务状态、无剩余名额、无逻辑删除列。 */
    @Test
    void contractShouldDeclareExactlyTwelveColumns() {
        assertThat(columnsOf(tableBlock)).containsExactlyInAnyOrder(
                "id", "task_id", "slot_id", "application_id", "worker_id", "status",
                "effective_at", "started_at", "delivered_at", "active_slot_id",
                "created_at", "updated_at");
    }

    /** 主键是雪花 ID：不得自增。 */
    @Test
    void primaryKeyShouldBeSnowflakeWithoutAutoIncrement() {
        assertThat(tableBlock).contains("PRIMARY KEY (id)");
        assertThat(effectiveDdl).doesNotContain("AUTO_INCREMENT");
    }

    /** 一份报名一生只可录用一次：application_id 全局唯一。 */
    @Test
    void applicationShouldBeGloballyUnique() {
        assertThat(tableBlock)
                .contains("UNIQUE KEY uk_engagement_contract_application (application_id)");
    }

    /** 「名额唯一」必须是「有效签约唯一」：取消后同一名额可再次录用（FR-SIGN.CANCEL.02）。 */
    @Test
    void activeSlotShouldBeUniqueByGeneratedColumn() {
        assertThat(tableBlock)
                .contains("UNIQUE KEY uk_engagement_contract_active_slot (active_slot_id)");
        assertThat(tableBlock).contains(
                "active_slot_id     BIGINT GENERATED ALWAYS AS (CASE WHEN status IN "
                        + "('CONTRACT_ACTIVE','CONTRACT_STARTED','CONTRACT_DELIVERED') "
                        + "THEN slot_id END) VIRTUAL");
        assertThat(count(tableBlock, "GENERATED ALWAYS AS")).isEqualTo(1);
    }

    /** 状态白名单必须与 ContractStatus 的五个取值逐字一致（A.3.1）。 */
    @Test
    void statusCheckShouldMatchFiveContractStates() {
        Matcher matcher = Pattern.compile("ck_engagement_contract_status CHECK \\(status IN \\(([^)]*)\\)\\)")
                .matcher(tableBlock);
        assertThat(matcher.find()).as("必须存在状态 CHECK 约束").isTrue();
        Set<String> whitelist = Pattern.compile("'([A-Z_]+)'").matcher(matcher.group(1)).results()
                .map(m -> m.group(1)).collect(java.util.stream.Collectors.toSet());
        assertThat(whitelist).containsExactlyInAnyOrder(
                "CONTRACT_ACTIVE", "CONTRACT_STARTED", "CONTRACT_DELIVERED",
                "CONTRACT_COMPLETED", "CONTRACT_CANCELLED");
    }

    /** 两个查询入口必须有索引：按任务、按劳动者；签约时间列必须可空、生效时间必须非空。 */
    @Test
    void shouldDeclareIndexesAndTimeColumnNullability() {
        assertThat(tableBlock)
                .contains("KEY idx_engagement_contract_task_time (task_id, effective_at)");
        assertThat(tableBlock)
                .contains("KEY idx_engagement_contract_worker_time (worker_id, effective_at)");
        assertThat(tableBlock).contains("effective_at       DATETIME(3) NOT NULL");
        assertThat(tableBlock).contains("started_at         DATETIME(3) NULL");
        assertThat(tableBlock).contains("delivered_at       DATETIME(3) NULL");
    }

    /** 更新时间只由库维护；创建时间也走库默认值。 */
    @Test
    void timestampsShouldBeMaintainedByDatabase() {
        assertThat(tableBlock).contains("created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)");
        assertThat(tableBlock).contains("ON UPDATE CURRENT_TIMESTAMP(3)");
    }

    /** 本卡只建签约：不得出现物理外键、金额小数、种子数据或结构变更语句。 */
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
                .doesNotContain("remaining_slots")
                .doesNotContain("deleted");
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
