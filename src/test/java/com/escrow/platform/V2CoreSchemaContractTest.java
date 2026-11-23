package com.escrow.platform;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.dispute.domain.Dispute;
import com.escrow.platform.dispute.domain.DisputeInitiator;
import com.escrow.platform.dispute.domain.DisputeResolution;
import com.escrow.platform.dispute.domain.DisputeStatus;
import com.escrow.platform.dispute.infrastructure.mapper.DisputeMapper;
import com.escrow.platform.fund.domain.AccountOwnerType;
import com.escrow.platform.fund.domain.AccountSubject;
import com.escrow.platform.fund.domain.EscrowDetailStatus;
import com.escrow.platform.fund.domain.EscrowOrderStatus;
import com.escrow.platform.fund.domain.FundAccount;
import com.escrow.platform.fund.domain.FundEscrowDetail;
import com.escrow.platform.fund.domain.FundEscrowOrder;
import com.escrow.platform.fund.domain.FundJournalBatch;
import com.escrow.platform.fund.domain.FundJournalEntry;
import com.escrow.platform.fund.domain.JournalBizType;
import com.escrow.platform.fund.domain.JournalDirection;
import com.escrow.platform.fund.domain.JournalEventType;
import com.escrow.platform.fund.domain.JournalTriggerType;
import com.escrow.platform.fund.infrastructure.mapper.FundAccountMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowDetailMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundEscrowOrderMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalBatchMapper;
import com.escrow.platform.fund.infrastructure.mapper.FundJournalEntryMapper;
import com.escrow.platform.settlement.domain.ChannelResult;
import com.escrow.platform.settlement.domain.DifferenceResolution;
import com.escrow.platform.settlement.domain.DifferenceStatus;
import com.escrow.platform.settlement.domain.DifferenceType;
import com.escrow.platform.settlement.domain.PayoutOrder;
import com.escrow.platform.settlement.domain.PayoutStatus;
import com.escrow.platform.settlement.domain.Settlement;
import com.escrow.platform.settlement.domain.SettlementDifference;
import com.escrow.platform.settlement.domain.SettlementStatus;
import com.escrow.platform.settlement.infrastructure.mapper.PayoutOrderMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementDifferenceMapper;
import com.escrow.platform.settlement.infrastructure.mapper.SettlementMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * V2 核心表契约测试（v2-c1 第 6 节）。
 *
 * <p>纯单元测试：不连库、不起 Spring 上下文。DDL 与 SRS 的一致性由反射和文本断言兜住，
 * 而不是靠「跑一次程序看看」。这样「MySQL 未启动时 {@code mvn test} 必须是绿的」
 * 这条纪律仍然成立（docs/decisions.md #33）。</p>
 *
 * <p>读脚本 → 剥掉 {@code --} 注释 → 用行首缩进正则断言列 / 约束，避免裸 contains("id")
 * 被 owner_user_id / success_settlement_id 误伤。</p>
 *
 * @since V2
 */
class V2CoreSchemaContractTest {

    // ==================== 迁移脚本断言（7 个 @Test） ====================

    @Test
    void migrationShouldCreateExactlyNineCoreTables() {
        String ddl = loadCleanDdl();

        Pattern createTable = Pattern.compile("CREATE TABLE\\s+([a-z_]+)");
        Matcher m = createTable.matcher(ddl);
        Set<String> actualTables = new java.util.HashSet<>();
        while (m.find()) {
            actualTables.add(m.group(1));
        }

        Set<String> expected = Set.of(
                "fund_account", "fund_escrow_order", "fund_escrow_detail",
                "fund_journal_batch", "fund_journal_entry",
                "settlement", "payout_order", "dispute", "settlement_difference");

        assertThat(actualTables).as("CREATE TABLE 出现次数 = 9；表名集合必须等于 §1.1 的九张").isEqualTo(expected);
    }

    @Test
    void migrationShouldNotTouchTablesOutsideThisCard() {
        String ddl = loadCleanDdl();

        // 结尾空格是刻意的：platform_user_identity 不是 platform_user，不能误伤
        assertThat(ddl).doesNotContain("CREATE TABLE platform_user ");
        assertThat(ddl).doesNotContain("CREATE TABLE platform_user_identity");
        assertThat(ddl).doesNotContain("CREATE TABLE platform_employer_qualification");
        assertThat(ddl).doesNotContain("CREATE TABLE task ");
        assertThat(ddl).doesNotContain("CREATE TABLE task_slot");
        assertThat(ddl).doesNotContain("CREATE TABLE dispute_evidence");
        assertThat(ddl).doesNotContain("CREATE TABLE platform_worker_profile");
    }

    @Test
    void migrationShouldContainNoBannedDdl() {
        String ddl = loadCleanDdl();

        assertThat(ddl).doesNotContain("FOREIGN KEY");
        assertThat(ddl).doesNotContain("AUTO_INCREMENT");
        assertThat(ddl).doesNotContain("\tdeleted ");
        assertThat(ddl).doesNotContain(" IF NOT EXISTS");
        assertThat(ddl).doesNotContain("ALTER TABLE");
        assertThat(ddl).doesNotContain("DROP ");
        assertThat(ddl).doesNotContain("INSERT INTO");
        assertThat(ddl).doesNotContain("DECIMAL");
        assertThat(ddl).doesNotContain("DOUBLE");
        assertThat(ddl).doesNotContain("FLOAT");
    }

    @Test
    void coreTablesShouldDeclareTheCardColumns() {
        String ddl = loadCleanDdl();

        // fund_account
        assertColumn(ddl, "id BIGINT");
        assertColumn(ddl, "owner_type VARCHAR(16)");
        assertColumn(ddl, "user_id BIGINT");
        assertColumn(ddl, "subject VARCHAR(20)");
        assertColumn(ddl, "balance BIGINT");
        assertColumn(ddl, "version INT");
        assertColumn(ddl, "created_at DATETIME(3)");
        assertColumn(ddl, "updated_at DATETIME(3)");
        assertColumn(ddl, "owner_user_id BIGINT");
        assertColumn(ddl, "system_subject VARCHAR(20)");

        // fund_escrow_order
        assertColumn(ddl, "task_id BIGINT");
        assertColumn(ddl, "total_amount BIGINT");
        assertColumn(ddl, "fee_rate INT");
        assertColumn(ddl, "status VARCHAR(32)");

        // fund_escrow_detail
        assertColumn(ddl, "escrow_order_id BIGINT");
        assertColumn(ddl, "slot_id BIGINT");
        assertColumn(ddl, "amount BIGINT");

        // fund_journal_batch
        assertColumn(ddl, "batch_no VARCHAR(32)");
        assertColumn(ddl, "biz_type VARCHAR(32)");
        assertColumn(ddl, "biz_no VARCHAR(32)");
        assertColumn(ddl, "event_type VARCHAR(32)");
        assertColumn(ddl, "trigger_type VARCHAR(16)");
        assertColumn(ddl, "summary VARCHAR(255)");

        // fund_journal_entry
        assertColumn(ddl, "journal_batch_id BIGINT");
        assertColumn(ddl, "account_id BIGINT");
        assertColumn(ddl, "direction VARCHAR(8)");

        // settlement
        assertColumn(ddl, "slot_id BIGINT");
        assertColumn(ddl, "settlement_no VARCHAR(32)");
        assertColumn(ddl, "payable_amount BIGINT");
        assertColumn(ddl, "fee_amount BIGINT");
        assertColumn(ddl, "net_amount BIGINT");
        assertColumn(ddl, "fee_rate INT");

        // payout_order
        assertColumn(ddl, "settlement_id BIGINT");
        assertColumn(ddl, "request_no VARCHAR(32)");
        assertColumn(ddl, "attempt_seq INT");
        assertColumn(ddl, "actual_amount BIGINT");
        assertColumn(ddl, "channel_result VARCHAR(20)");
        assertColumn(ddl, "status_entered_at DATETIME(3)");
        assertColumn(ddl, "success_settlement_id BIGINT");

        // dispute
        assertColumn(ddl, "initiator_type VARCHAR(16)");
        assertColumn(ddl, "reason VARCHAR(500)");
        assertColumn(ddl, "resolution VARCHAR(16)");
        assertColumn(ddl, "resolver_id BIGINT");
        assertColumn(ddl, "resolved_at DATETIME(3)");
        assertColumn(ddl, "withdraw_count INT");

        // settlement_difference（recon_date 是 DATE，必须单独断言）
        assertColumn(ddl, "recon_date DATE");
        assertColumn(ddl, "diff_type VARCHAR(24)");
        assertColumn(ddl, "local_record_no VARCHAR(32)");
        assertColumn(ddl, "channel_record_no VARCHAR(32)");
        assertColumn(ddl, "local_amount BIGINT");
        assertColumn(ddl, "channel_amount BIGINT");
        assertColumn(ddl, "resolution VARCHAR(24)");
    }

    @Test
    void statusColumnsShouldBeVarchar32() {
        String ddl = loadCleanDdl();

        // 9 张表的 status 列全部 VARCHAR(32)
        assertColumnType(ddl, "fund_escrow_order", "status", "VARCHAR(32)");
        assertColumnType(ddl, "fund_escrow_detail", "status", "VARCHAR(32)");
        assertColumnType(ddl, "settlement", "status", "VARCHAR(32)");
        assertColumnType(ddl, "payout_order", "status", "VARCHAR(32)");
        assertColumnType(ddl, "dispute", "status", "VARCHAR(32)");
        assertColumnType(ddl, "settlement_difference", "status", "VARCHAR(32)");

        // fund_journal_batch / fund_journal_entry 没有 status 列
        assertThat(ddl).doesNotMatch("(?m)^\\s+status\\s+VARCHAR\\(32\\).*,");
        // 用更精准的断言：搜索 CREATE TABLE fund_journal_batch 到下一个 CREATE TABLE 之间的 status
        assertThat(extractTable(ddl, "fund_journal_batch")).doesNotContain("status");
        assertThat(extractTable(ddl, "fund_journal_entry")).doesNotContain("status");
    }

    @Test
    void journalLedgerTablesShouldBeAppendOnly() {
        String ddl = loadCleanDdl();

        // fund_journal_batch 只有 created_at DATETIME(3)，没有 updated_at / deleted / status
        String batchDdl = extractTable(ddl, "fund_journal_batch");
        assertThat(batchDdl).containsPattern("(?m)^\\s+created_at\\s+DATETIME\\(3\\)");
        assertThat(batchDdl).doesNotContain("updated_at");
        assertThat(batchDdl).doesNotContain("deleted");
        assertThat(batchDdl).doesNotContain("status");

        // fund_journal_entry 同理
        String entryDdl = extractTable(ddl, "fund_journal_entry");
        assertThat(entryDdl).containsPattern("(?m)^\\s+created_at\\s+DATETIME\\(3\\)");
        assertThat(entryDdl).doesNotContain("updated_at");
        assertThat(entryDdl).doesNotContain("deleted");
        assertThat(entryDdl).doesNotContain("status");
    }

    @Test
    void constraintsShouldEncodeTheThreeCoreRules() {
        String ddl = loadCleanDdl();

        // ① 幂等键 + batch_no 唯一键同时存在
        assertThat(ddl).containsPattern("uk_fund_journal_batch_biz");
        assertThat(ddl).containsPattern("uk_fund_journal_batch_no");

        // ② journal_batch_id BIGINT
        assertThat(ddl).containsPattern("journal_batch_id\\s+BIGINT");

        // ③ 结算服务费公式
        assertThat(ddl).contains("FLOOR(payable_amount * fee_rate / 10000)");
        assertThat(ddl).contains("fee_amount + net_amount = payable_amount");

        // ④ 三个生成列
        assertThat(ddl).contains("GENERATED ALWAYS AS");
        assertThat(ddl).containsPattern("owner_user_id.*GENERATED ALWAYS AS");
        assertThat(ddl).containsPattern("system_subject.*GENERATED ALWAYS AS");
        assertThat(ddl).containsPattern("success_settlement_id.*GENERATED ALWAYS AS");

        // 三个唯一索引存在
        assertThat(ddl).containsPattern("uk_fund_account_user");
        assertThat(ddl).containsPattern("uk_fund_account_system");
        assertThat(ddl).containsPattern("uk_payout_success");
    }

    // ==================== 实体断言（2 个 @Test） ====================

    @Test
    void entitiesShouldExposeExactlyTheCardFields() {
        assertThat(instanceFieldNames(FundAccount.class))
                .containsExactly("id", "ownerType", "userId", "subject", "balance", "version", "createdAt", "updatedAt");

        assertThat(instanceFieldNames(FundEscrowOrder.class))
                .containsExactly("id", "taskId", "totalAmount", "feeRate", "status", "createdAt", "updatedAt");

        assertThat(instanceFieldNames(FundEscrowDetail.class))
                .containsExactly("id", "escrowOrderId", "slotId", "amount", "status", "createdAt", "updatedAt");

        assertThat(instanceFieldNames(FundJournalBatch.class))
                .containsExactly("id", "batchNo", "bizType", "bizNo", "eventType", "triggerType", "summary", "createdAt");

        assertThat(instanceFieldNames(FundJournalEntry.class))
                .containsExactly("id", "journalBatchId", "accountId", "subject", "direction", "amount", "createdAt");

        assertThat(instanceFieldNames(Settlement.class))
                .containsExactly("id", "slotId", "settlementNo", "payableAmount", "feeAmount", "netAmount", "feeRate", "status", "createdAt", "updatedAt");

        assertThat(instanceFieldNames(PayoutOrder.class))
                .containsExactly("id", "settlementId", "requestNo", "attemptSeq", "amount", "actualAmount", "channelResult", "status", "statusEnteredAt", "createdAt", "updatedAt");

        assertThat(instanceFieldNames(Dispute.class))
                .containsExactly("id", "slotId", "initiatorType", "reason", "status", "resolution",
                        "resolutionBasis", "resolverId", "resolvedAt", "withdrawCount", "createdAt",
                        "updatedAt");

        assertThat(instanceFieldNames(SettlementDifference.class))
                .containsExactly("id", "reconDate", "diffType", "localRecordNo", "channelRecordNo", "localAmount", "channelAmount", "status", "resolution", "resolverId", "resolvedAt", "createdAt", "updatedAt");
    }

    @Test
    void entitiesShouldMapTheRightTablesAndPrimaryKeys() throws Exception {
        // @TableName 的 value() 等于对应表名
        assertThat(FundAccount.class.getAnnotation(TableName.class).value()).isEqualTo("fund_account");
        assertThat(FundEscrowOrder.class.getAnnotation(TableName.class).value()).isEqualTo("fund_escrow_order");
        assertThat(FundEscrowDetail.class.getAnnotation(TableName.class).value()).isEqualTo("fund_escrow_detail");
        assertThat(FundJournalBatch.class.getAnnotation(TableName.class).value()).isEqualTo("fund_journal_batch");
        assertThat(FundJournalEntry.class.getAnnotation(TableName.class).value()).isEqualTo("fund_journal_entry");
        assertThat(Settlement.class.getAnnotation(TableName.class).value()).isEqualTo("settlement");
        assertThat(PayoutOrder.class.getAnnotation(TableName.class).value()).isEqualTo("payout_order");
        assertThat(Dispute.class.getAnnotation(TableName.class).value()).isEqualTo("dispute");
        assertThat(SettlementDifference.class.getAnnotation(TableName.class).value()).isEqualTo("settlement_difference");

        // id 字段带 @TableId
        assertThat(FundAccount.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();
        assertThat(FundEscrowOrder.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();
        assertThat(FundEscrowDetail.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();
        assertThat(FundJournalBatch.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();
        assertThat(FundJournalEntry.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();
        assertThat(Settlement.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();
        assertThat(PayoutOrder.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();
        assertThat(Dispute.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();
        assertThat(SettlementDifference.class.getDeclaredField("id").isAnnotationPresent(TableId.class)).isTrue();

        // 生成列不进实体
        assertThat(instanceFieldNames(FundAccount.class)).doesNotContain("ownerUserId", "systemSubject");
        assertThat(instanceFieldNames(PayoutOrder.class)).doesNotContain("successSettlementId");

        // SettlementDifference.reconDate 类型是 LocalDate，其余时间字段是 LocalDateTime
        Field reconDateField = SettlementDifference.class.getDeclaredField("reconDate");
        assertThat(reconDateField.getType()).isEqualTo(LocalDate.class);

        Field createdAtField = SettlementDifference.class.getDeclaredField("createdAt");
        assertThat(createdAtField.getType()).isEqualTo(LocalDateTime.class);
    }

    // ==================== 枚举断言（4 个 @Test） ====================

    @Test
    void enumsShouldDeclareExactConstantsInOrder() {
        assertThat(constantNames(AccountOwnerType.class)).containsExactly("USER", "SYSTEM");
        assertThat(constantNames(AccountSubject.class)).containsExactly(
                "ACC_AVAIL", "ACC_FROZEN", "ACC_PLATFORM", "ACC_TRANSIT", "ACC_WITHDRAWN", "ACC_ISSUED");
        assertThat(constantNames(EscrowOrderStatus.class)).containsExactly("ESCROW_ORDER_HELD", "ESCROW_ORDER_CLOSED");
        assertThat(constantNames(EscrowDetailStatus.class)).containsExactly(
                "ESCROW_DETAIL_PENDING_HOLD", "ESCROW_DETAIL_HELD", "ESCROW_DETAIL_RELEASING",
                "ESCROW_DETAIL_RELEASED", "ESCROW_DETAIL_REFUNDED", "ESCROW_DETAIL_FROZEN");
        assertThat(constantNames(JournalDirection.class)).containsExactly("DEBIT", "CREDIT");
        assertThat(constantNames(JournalTriggerType.class)).containsExactly("USER", "SYSTEM", "OPERATOR");
        assertThat(constantNames(JournalBizType.class)).containsExactly(
                "RECHARGE", "ESCROW_HOLD", "ESCROW_REFUND",
                "ESCROW_RELEASE", "SETTLE_ZERO_PAY", "PAYOUT_SETTLE", "RECON_ADJUST");
        assertThat(constantNames(JournalEventType.class)).containsExactly(
                "ACCOUNT_ISSUE", "ESCROW_HOLD", "ESCROW_REFUND",
                "ESCROW_RELEASE", "SETTLE_ZERO_PAY", "PAYOUT_SETTLE",
                "RECON_BOOK", "RECON_REVERSE", "RECON_DIFF_ADJUST");
        assertThat(constantNames(ChannelResult.class)).containsExactly("SUCCESS", "FAIL", "TIMEOUT", "LOST_RESPONSE");
        assertThat(constantNames(SettlementStatus.class)).containsExactly(
                "SETTLEMENT_PENDING_GENERATE", "SETTLEMENT_CONFIRMED", "SETTLEMENT_PAYING",
                "SETTLEMENT_PENDING_RETRY", "SETTLEMENT_COMPLETED", "SETTLEMENT_VOIDED");
        assertThat(constantNames(PayoutStatus.class)).containsExactly(
                "PAYOUT_PENDING_EXECUTE", "PAYOUT_EXECUTING", "PAYOUT_SUCCESS", "PAYOUT_FAIL",
                "PAYOUT_PENDING_VERIFICATION", "PAYOUT_MANUAL_REVIEW");
        assertThat(constantNames(DisputeInitiator.class)).containsExactly("EMPLOYER", "WORKER");
        assertThat(constantNames(DisputeStatus.class)).containsExactly(
                "DISPUTE_PENDING", "DISPUTE_WITHDRAWN", "DISPUTE_RESOLVED");
        assertThat(constantNames(DisputeResolution.class)).containsExactly("RELEASE", "REFUND");
        assertThat(constantNames(DifferenceResolution.class)).containsExactly(
                "RECON_BOOK", "RECON_REVERSE", "RECON_ADJUST", "RECON_IGNORE");
        assertThat(constantNames(DifferenceType.class)).containsExactly(
                "CHANNEL_MISSING", "LOST_RESPONSE", "AMOUNT_MISMATCH", "RESULT_UNDETERMINED");
        assertThat(constantNames(DifferenceStatus.class)).containsExactly(
                "SETTLEMENT_DIFF_PENDING", "SETTLEMENT_DIFF_HANDLED", "SETTLEMENT_DIFF_IGNORED");
    }

    @Test
    void everyEnumShouldStoreItsNameThroughSingleEnumValueField() throws Exception {
        List<Class<? extends Enum<?>>> enumTypes = List.of(
                AccountOwnerType.class, AccountSubject.class, EscrowOrderStatus.class, EscrowDetailStatus.class,
                JournalDirection.class, JournalTriggerType.class, JournalBizType.class, JournalEventType.class,
                ChannelResult.class, SettlementStatus.class, PayoutStatus.class,
                DifferenceResolution.class, DifferenceType.class, DifferenceStatus.class,
                DisputeInitiator.class, DisputeStatus.class, DisputeResolution.class);

        for (Class<? extends Enum<?>> type : enumTypes) {
            List<Field> annotatedFields = Arrays.stream(type.getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(EnumValue.class))
                    .toList();

            assertThat(annotatedFields)
                    .as("%s 必须且只能有一个 @EnumValue 字段", type.getSimpleName())
                    .hasSize(1);

            Field enumValueField = annotatedFields.get(0);
            assertThat(enumValueField.getType())
                    .as("%s 的 @EnumValue 字段必须是 String", type.getSimpleName())
                    .isEqualTo(String.class);

            enumValueField.setAccessible(true);
            for (Enum<?> constant : type.getEnumConstants()) {
                assertThat(enumValueField.get(constant))
                        .as("%s.%s 的落库值必须等于 name()", type.getSimpleName(), constant.name())
                        .isEqualTo(constant.name());
            }
        }
    }

    @Test
    void enumConstantNamesShouldNotExceed28Chars() {
        List<Class<? extends Enum<?>>> enumTypes = List.of(
                AccountOwnerType.class, AccountSubject.class, EscrowOrderStatus.class, EscrowDetailStatus.class,
                JournalDirection.class, JournalTriggerType.class, JournalBizType.class, JournalEventType.class,
                ChannelResult.class, SettlementStatus.class, PayoutStatus.class,
                DifferenceResolution.class, DifferenceType.class, DifferenceStatus.class,
                DisputeInitiator.class, DisputeStatus.class, DisputeResolution.class);

        for (Class<? extends Enum<?>> type : enumTypes) {
            for (Enum<?> constant : type.getEnumConstants()) {
                assertThat(constant.name().length())
                        .as("%s.%s 常量名必须 <= 28 字符（docs/decisions.md #20【V2 修订】）",
                                type.getSimpleName(), constant.name())
                        .isLessThanOrEqualTo(28);
            }
        }
    }

    @Test
    void journalTypesShouldBeFinalizedEnums() throws Exception {
        // c1 的留痕断言（JournalEventType 不存在）在 v2-c5 按预期翻转：类型定稿为枚举
        assertThat(Class.forName("com.escrow.platform.fund.domain.JournalEventType"))
                .isEqualTo(JournalEventType.class);
        assertThat(Class.forName("com.escrow.platform.fund.domain.JournalBizType"))
                .isEqualTo(JournalBizType.class);

        assertThat(FundJournalBatch.class.getDeclaredField("bizType").getType())
                .isEqualTo(JournalBizType.class);
        assertThat(FundJournalBatch.class.getDeclaredField("eventType").getType())
                .isEqualTo(JournalEventType.class);
    }

    // ==================== Mapper 断言（1 个 @Test） ====================

    @Test
    void mappersShouldBeNineEmptyBaseMappers() throws Exception {
        List<Class<?>> mappers = List.of(
                FundAccountMapper.class, FundEscrowOrderMapper.class, FundEscrowDetailMapper.class,
                FundJournalBatchMapper.class, FundJournalEntryMapper.class,
                SettlementMapper.class, PayoutOrderMapper.class, SettlementDifferenceMapper.class,
                DisputeMapper.class);

        assertThat(mappers).hasSize(9);

        for (Class<?> type : mappers) {
            assertThat(type.isInterface())
                    .as("%s 必须是 interface", type.getSimpleName())
                    .isTrue();

            assertThat(BaseMapper.class.isAssignableFrom(type))
                    .as("%s 必须继承 BaseMapper", type.getSimpleName())
                    .isTrue();

            assertThat(type.getDeclaredMethods())
                    .as("%s 不声明任何方法", type.getSimpleName())
                    .isEmpty();

            assertThat(type.isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class))
                    .as("%s 不带 @Mapper（docs/decisions.md #44）", type.getSimpleName())
                    .isFalse();
        }
    }

    // ==================== 辅助方法 ====================

    /** 读取 V4 DDL 脚本并剥掉 -- 注释行。 */
    private String loadCleanDdl() {
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V4__v2_core_tables.sql")) {
            assertThat(in)
                    .as("V4 迁移脚本必须在 src/main/resources/db/migration 下")
                    .isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("读取 V4__v2_core_tables.sql 失败", e);
        }

        return Arrays.stream(sql.split("\\R"))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    /** 行首缩进正则断言列存在。 */
    private void assertColumn(String ddl, String colDef) {
        String[] parts = colDef.split("\\s+");
        String colName = parts[0];
        String colType = parts.length >= 2 ? parts[1] : "";
        // 对于带括号的类型如 VARCHAR(16)，直接作为正则片段
        String pattern = "(?m)^\\s+" + Pattern.quote(colName) + "\\s+" + Pattern.quote(colType);
        assertThat(ddl)
                .as("DDL 应包含列定义：" + colDef)
                .containsPattern(pattern);
    }

    /** 断言某张表中某列的类型。 */
    private void assertColumnType(String ddl, String tableName, String columnName, String expectedType) {
        String tableDdl = extractTable(ddl, tableName);
        String pattern = "(?m)^\\s+" + Pattern.quote(columnName) + "\\s+" + Pattern.quote(expectedType);
        assertThat(tableDdl)
                .as("%s 表中 %s 列必须是 %s", tableName, columnName, expectedType)
                .containsPattern(pattern);
    }

    /** 从完整 DDL 中抽出单张表的 CREATE TABLE ... ) ENGINE= 块。 */
    private String extractTable(String ddl, String tableName) {
        Pattern p = Pattern.compile("CREATE TABLE\\s+" + tableName + "\\s*\\((.*?)\\)\\s+ENGINE", Pattern.DOTALL);
        Matcher m = p.matcher(ddl);
        assertThat(m.find()).as("找不到 CREATE TABLE " + tableName).isTrue();
        return m.group(1);
    }

    /** 取枚举常量名，保持声明顺序。 */
    private static List<String> constantNames(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    /** 取实体的实例字段名，保持声明顺序。 */
    private static List<String> instanceFieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .toList();
    }
}
