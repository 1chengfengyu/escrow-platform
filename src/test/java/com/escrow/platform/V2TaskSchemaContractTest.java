package com.escrow.platform;

import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 迁移结构契约测试（v2-c2 卡片 §9，9 个用例，无 MySQL）。
 *
 * <p>锁死四类「改了就炸」的事实：表数量、列集与实体字段互为镜像、
 * 派生口径（task 无 status / remaining_slots）、枚举落库值与 CHECK 白名单逐字一致。
 * 照 {@code V2CoreSchemaContractTest} 的读文件断言手法。</p>
 *
 * @since V2
 */
class V2TaskSchemaContractTest {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?m)^CREATE TABLE ([a-z_]+) \\($");
    /** 列行 = 缩进 + 小写标识符 + 类型关键字 + 空白；约束 / 索引行天然不匹配。 */
    private static final Pattern COLUMN = Pattern.compile(
            "(?m)^\\s+([a-z_]+)\\s+(?:BIGINT|VARCHAR\\(\\d+\\)|DATETIME\\(3\\)|INT|TINYINT\\(1\\))(?=\\s)");

    private static String ddl;
    private static String taskBlock;
    private static String taskSlotBlock;

    @BeforeAll
    static void readMigration() throws IOException {
        ddl = Files.readString(Path.of("src/main/resources/db/migration", "V5__v2_task_tables.sql"));
        taskBlock = extractTable("task");
        taskSlotBlock = extractTable("task_slot");
    }

    /** 迁移必须恰好建 task / task_slot 两张表（不多建第三张）。 */
    @Test
    void migrationShouldCreateExactlyTwoTables() {
        List<String> tables = CREATE_TABLE.matcher(ddl).results()
                .map(m -> m.group(1)).toList();
        assertThat(tables).containsExactly("task", "task_slot");
    }

    /** task 必须 17 列，且与预期列集一致（无多无少）。 */
    @Test
    void taskShouldDeclareExactly17Columns() {
        assertThat(columnsOf(taskBlock)).containsExactlyInAnyOrder(
                "id", "employer_id", "title", "description", "category",
                "unit_price", "slot_count", "start_time", "end_time",
                "work_location", "settlement_note", "fee_rate",
                "published_at", "cancelled_at", "recruiting_closed",
                "created_at", "updated_at");
    }

    /** task 建表块内不得出现 status / remaining_slots（先截取建表块，避免误伤 task_slot.status）。 */
    @Test
    void taskBlockShouldNotDeclareStatusOrRemainingSlots() {
        assertThat(taskBlock)
                .doesNotContain("status")
                .doesNotContain("remaining_slots");
    }

    /** task_slot 必须 8 列，且与预期列集一致。 */
    @Test
    void taskSlotShouldDeclareExactly8Columns() {
        assertThat(columnsOf(taskSlotBlock)).containsExactlyInAnyOrder(
                "id", "task_id", "seq_no", "status", "application_id",
                "engagement_id", "created_at", "updated_at");
    }

    /** 硬红线：无物理外键 / 自增 / 浮点金额 / IF NOT EXISTS / DROP / ALTER（先剥掉 `--` 注释行再断言）。 */
    @Test
    void migrationShouldContainNoBannedDdl() {
        // 头部注释按卡片 §2.4 逐字含「无 AUTO_INCREMENT」字样，禁词检查只针对生效 DDL
        String effectiveDdl = ddl.replaceAll("(?m)^--.*$", "");
        assertThat(effectiveDdl)
                .doesNotContain("AUTO_INCREMENT")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES")
                .doesNotContain("DECIMAL")
                .doesNotContain("DOUBLE")
                .doesNotContain("FLOAT")
                .doesNotContain("IF NOT EXISTS")
                .doesNotContain("DROP ")
                .doesNotContain("ALTER ");
    }

    /** 名额索引必须与设计一致：业务键 (task_id, seq_no) 唯一 + 状态聚合索引。 */
    @Test
    void taskSlotIndexesShouldExist() {
        assertThat(taskSlotBlock)
                .contains("UNIQUE KEY uk_task_slot_task_seq (task_id, seq_no)")
                .contains("KEY idx_task_slot_task_status (task_id, status)");
    }

    /** SlotStatus 九个常量必须与 ck_task_slot_status 白名单逐字一致（防「插入即被拒」）。 */
    @Test
    void slotStatusEnumShouldMatchCheckWhitelist() {
        Matcher m = Pattern.compile(
                        "ck_task_slot_status\\s+CHECK \\(status IN \\(([^)]*)\\)\\)")
                .matcher(taskSlotBlock);
        assertThat(m.find()).as("ck_task_slot_status 约束必须存在").isTrue();

        List<String> whitelist = Arrays.stream(m.group(1).split(","))
                .map(item -> item.trim().replace("'", ""))
                .toList();
        List<String> enumNames = Arrays.stream(SlotStatus.values())
                .map(Enum::name)
                .toList();
        assertThat(whitelist).containsExactlyElementsOf(enumNames);
    }

    /** Task 实体字段（camelCase → snake_case）必须与 task 列集互为镜像。 */
    @Test
    void taskEntityFieldsShouldMapTaskColumns() {
        assertThat(entityColumns(Task.class)).isEqualTo(columnsOf(taskBlock));
    }

    /** TaskSlot 实体字段必须与 task_slot 列集互为镜像。 */
    @Test
    void taskSlotEntityFieldsShouldMapTaskSlotColumns() {
        assertThat(entityColumns(TaskSlot.class)).isEqualTo(columnsOf(taskSlotBlock));
    }

    // ==================== 解析辅助 ====================

    /** 截取某表的建表块：从 {@code CREATE TABLE xxx (} 到 {@code ) ENGINE=}。 */
    private static String extractTable(String table) {
        String header = "CREATE TABLE " + table + " (";
        int start = ddl.indexOf(header);
        assertThat(start).as("迁移里必须能找到 %s", header).isGreaterThanOrEqualTo(0);
        int end = ddl.indexOf(") ENGINE=", start);
        assertThat(end).as("%s 的建表块必须以 ) ENGINE= 收尾", table).isGreaterThan(start);
        return ddl.substring(start, end);
    }

    /** 从建表块解析列名：列行 = 缩进 + 小写标识符 + 类型关键字；约束 / 索引行天然不匹配。 */
    private static Set<String> columnsOf(String block) {
        return COLUMN.matcher(block).results()
                .map(m -> m.group(1))
                .collect(Collectors.toSet());
    }

    /** 实体字段集（排除 static），camelCase 转 snake_case。 */
    private static Set<String> entityColumns(Class<?> entity) {
        return Arrays.stream(entity.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(f -> camelToSnake(f.getName()))
                .collect(Collectors.toSet());
    }

    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
