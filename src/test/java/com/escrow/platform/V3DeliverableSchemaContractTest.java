package com.escrow.platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 交付物表结构契约测试（v3-c4，FR-DELIVER.SUBMIT.01~.03）。
 *
 * <p>纯单元测试：只读迁移脚本，不连 MySQL，保持「MySQL 未启动时 {@code mvn test} 必须是绿的」。</p>
 *
 * <p>锁死改了就炸的事实：表数量（恰好一张）、列集、{@code UNIQUE(contract_id)}、
 * 图片列表以 JSON 文本存一列（不建子表）、无金额小数与逻辑删除。</p>
 *
 * @since V3
 */
class V3DeliverableSchemaContractTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration", "V10__v3_engagement_deliverable.sql");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?m)^CREATE TABLE ([a-z_]+) \\($");
    /** 列行 = 缩进 + 小写标识符 + 类型关键字 + 空白；约束 / 索引行天然不匹配。 */
    private static final Pattern COLUMN = Pattern.compile(
            "(?m)^\\s+([a-z_]+)\\s+(?:BIGINT|VARCHAR\\(\\d+\\)|DATETIME\\(3\\))(?=\\s)");
    /** 列类型为 {@code JSON} 的行（注释里提到 JSON 不算）。 */
    private static final Pattern JSON_COLUMN = Pattern.compile("(?m)^\\s+[a-z_]+\\s+JSON(?=\\s)");

    private static String effectiveDdl;
    private static String tableBlock;

    @BeforeAll
    static void readMigration() throws IOException {
        effectiveDdl = Files.readString(MIGRATION).replaceAll("(?m)^--.*$", "");
        int start = effectiveDdl.indexOf("CREATE TABLE engagement_deliverable (");
        int end = effectiveDdl.indexOf(") ENGINE=InnoDB", start);
        assertThat(start).as("必须存在 engagement_deliverable 建表块").isGreaterThanOrEqualTo(0);
        assertThat(end).as("建表块必须以 ENGINE=InnoDB 结束").isGreaterThan(0);
        tableBlock = effectiveDdl.substring(start, end);
    }

    /** 迁移必须恰好建一张表：交付物不允许拆子表。 */
    @Test
    void migrationShouldCreateExactlyOneTable() {
        List<String> tables = CREATE_TABLE.matcher(effectiveDdl).results()
                .map(m -> m.group(1)).toList();
        assertThat(tables).containsExactly("engagement_deliverable");
    }

    /** 列集：契约 ID + 劳动者 + 图片 JSON + 说明 + 提交时间；只追加，无更新时间。 */
    @Test
    void deliverableShouldDeclareExactlySevenColumns() {
        assertThat(columnsOf(tableBlock)).containsExactlyInAnyOrder(
                "id", "contract_id", "worker_id", "image_urls", "description",
                "submitted_at", "created_at");
    }

    /** 主键是雪花 ID：不得自增。 */
    @Test
    void primaryKeyShouldBeSnowflakeWithoutAutoIncrement() {
        assertThat(tableBlock).contains("PRIMARY KEY (id)");
        assertThat(effectiveDdl).doesNotContain("AUTO_INCREMENT");
    }

    /** 一个签约至多一份交付物：由唯一索引兜底重复提交。 */
    @Test
    void contractShouldBeUnique() {
        assertThat(tableBlock)
                .contains("UNIQUE KEY uk_engagement_deliverable_contract (contract_id)");
    }

    /** 图片列表存 JSON 数组文本（单列 VARCHAR(3000)），不建子表、不用 JSON 列类型。 */
    @Test
    void imageUrlsShouldBeSingleJsonTextColumn() {
        assertThat(tableBlock).contains("image_urls   VARCHAR(3000) NOT NULL");
        assertThat(JSON_COLUMN.matcher(tableBlock).find())
                .as("不得使用 JSON 列类型")
                .isFalse();
    }

    /** 说明可空；提交时间是验收倒计时起点，必须非空；按劳动者查历史必须有索引。 */
    @Test
    void shouldDeclareNullableDescriptionAndWorkerIndex() {
        assertThat(tableBlock).contains("description  VARCHAR(500) NULL");
        assertThat(tableBlock).contains("submitted_at DATETIME(3)  NOT NULL");
        assertThat(tableBlock).contains(
                "KEY idx_engagement_deliverable_worker_time (worker_id, submitted_at)");
    }

    /** 本卡只建交付物表：不得出现物理外键、金额小数、种子数据或结构变更语句。 */
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
