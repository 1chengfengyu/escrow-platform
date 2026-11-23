package com.escrow.platform.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份持久层契约测试（v1-c2 第 1.6 节，依据 FR-ACCT.ROLE.01~ROLE.03 与 docs/decisions.md #18~#22）。
 *
 * <p>纯单元测试：不连库、不起 Spring 上下文。DDL 与 SRS 的一致性由反射和文本断言兜住，
 * 而不是靠「跑一次程序看看」。这样「MySQL 未启动时 {@code mvn test} 必须是绿的」
 * 这条纪律仍然成立（docs/decisions.md #33）。</p>
 *
 * <p>为什么值得写：枚举常量顺序、落库值、实体字段、迁移脚本四者只要有任意一处漂移，
 * 编译期都发现不了，只有到运行期才会以「查不出数据」「换身份无效」的形式暴露。</p>
 *
 * @since V1
 */
class IdentitySchemaContractTest {

    /** FR-ACCT.ROLE.01：雇主与劳动者身份可并存，注册默认身份三选一，顺序固定。 */
    @Test
    void userRoleConstantsShouldMatchContract() {
        assertThat(constantNames(UserRole.class))
                .containsExactly("EMPLOYER", "WORKER", "ADMIN");
    }

    /** docs/decisions.md #20 的状态取值表：ACTIVE 可登录，DISABLED / CLOSED 不可登录。 */
    @Test
    void userStatusConstantsShouldMatchContract() {
        assertThat(constantNames(UserStatus.class))
                .containsExactly("ACTIVE", "DISABLED", "CLOSED");
    }

    /** SRS 4.2.2「用户身份」行：身份类型三个值，顺序固定。 */
    @Test
    void identityTypeConstantsShouldMatchContract() {
        assertThat(constantNames(IdentityType.class))
                .containsExactly("EMPLOYER", "WORKER", "ADMIN");
    }

    /** FR-ACCT.ROLE.02：身份只有「可用 / 禁用」两态。 */
    @Test
    void identityStatusConstantsShouldMatchContract() {
        assertThat(constantNames(IdentityStatus.class))
                .containsExactly("ACTIVE", "DISABLED");
    }

    /**
     * 落库值 = 枚举名（docs/decisions.md #20）。
     *
     * <p>逐个枚举检查：必须且只能有一个 {@code @EnumValue} 字段，类型 {@code String}，
     * 且每个常量的字段值等于 {@code name()}。少了注解会存序号（0/1/2），
     * 多了注解则两个字段争用，MP 只认一个，存出来的值不可预期。</p>
     */
    @Test
    void everyEnumShouldStoreItsNameThroughSingleEnumValueField() throws Exception {
        List<Class<? extends Enum<?>>> enumTypes = List.of(
                UserRole.class, UserStatus.class, IdentityType.class, IdentityStatus.class);

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

            // private 字段，反射前必须放开访问；本测试只读不写，不影响枚举单例。
            enumValueField.setAccessible(true);
            for (Enum<?> constant : type.getEnumConstants()) {
                assertThat(enumValueField.get(constant))
                        .as("%s.%s 的落库值必须等于 name()", type.getSimpleName(), constant.name())
                        .isEqualTo(constant.name());
            }
        }
    }

    /** docs/decisions.md #19：platform_user 九列，实体字段名必须与之一一对应（多一个少一个都是漂移）。 */
    @Test
    void platformUserShouldExposeExactlyNineColumns() {
        assertThat(instanceFieldNames(PlatformUser.class))
                .containsExactlyInAnyOrder(
                        "id", "phone", "email", "nickname", "passwordHash",
                        "role", "status", "createdAt", "updatedAt");
    }

    /** V2 DDL 六列：实体字段名必须与之一一对应。 */
    @Test
    void platformUserIdentityShouldExposeExactlySixColumns() {
        assertThat(instanceFieldNames(PlatformUserIdentity.class))
                .containsExactlyInAnyOrder(
                        "id", "userId", "identityType", "status", "createdAt", "updatedAt");
    }

    /**
     * 迁移脚本必须真正落在 classpath 且内容受控：
     * 只建身份表、六列齐全、不含 {@code CREATE TABLE platform_user}（V0 已建，重复建表必炸）、
     * 不含物理外键（跨表一致性由应用保证）。
     *
     * <p>断言前先剥掉 {@code --} 注释行：脚本头部的禁令注释本身就写了
     * 「不许出现 CREATE TABLE platform_user」，不剥离会把注释误判成建表语句。</p>
     */
    @Test
    void migrationScriptShouldCreateOnlyIdentityTableWithSixColumns() throws Exception {
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V2__platform_user_identity.sql")) {
            assertThat(in)
                    .as("V2 迁移脚本必须在 src/main/resources/db/migration 下")
                    .isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 只保留可执行语句，注释不参与 DDL 契约断言。
        String ddl = Arrays.stream(sql.split("\\R"))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));

        assertThat(ddl).contains("CREATE TABLE platform_user_identity");

        // 用「行首缩进 + 列名 + 类型」断言，避免 contains("id") 被 identity_type 之类误判为通过。
        assertThat(ddl).containsPattern("(?m)^\\s+id\\s+BIGINT");
        assertThat(ddl).containsPattern("(?m)^\\s+user_id\\s+BIGINT");
        assertThat(ddl).containsPattern("(?m)^\\s+identity_type\\s+VARCHAR\\(20\\)");
        assertThat(ddl).containsPattern("(?m)^\\s+status\\s+VARCHAR\\(20\\)");
        assertThat(ddl).containsPattern("(?m)^\\s+created_at\\s+DATETIME\\(3\\)");
        assertThat(ddl).containsPattern("(?m)^\\s+updated_at\\s+DATETIME\\(3\\)");

        // 结尾空格是刻意的：platform_user_identity 不是 platform_user，不能误伤。
        assertThat(ddl).doesNotContain("CREATE TABLE platform_user ");
        assertThat(ddl).doesNotContain("FOREIGN KEY");
    }

    /** 取枚举常量名，保持声明顺序。 */
    private static List<String> constantNames(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    /**
     * 取实体的实例字段名。
     *
     * <p>过滤 static 与 synthetic：MP 与 Lombok 都不生成额外实例字段，
     * 一旦出现就说明有人加了非持久字段或把常量写进了实体。</p>
     */
    private static Set<String> instanceFieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toSet());
    }
}
