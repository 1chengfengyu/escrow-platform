package com.escrow.platform.identity.application;

import com.escrow.platform.identity.api.RegisterRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册字段校验单测（FR-ACCT.REG.01）。
 *
 * <p>纯 JUnit：不连库、不起 Spring，保证「MySQL 未启动时 {@code mvn test} 必须是绿的」。</p>
 *
 * @since V1
 */
class RegisterValidatorTest {

    private final RegisterValidator validator = new RegisterValidator();

    /** 合法基线：只填手机号亦可注册（FR-ACCT.REG.01 允许手机号或邮箱二选一）。 */
    @Test
    void shouldPassWithValidPhoneOnly() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("13800138000", null, "abc12345", "小明", "WORKER"));

        assertThat(fields).isEmpty();
    }

    /** 合法基线：只填邮箱亦可注册。 */
    @Test
    void shouldPassWithValidEmailOnly() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest(null, "user.name+tag@example.com", "abc12345", "小明", "EMPLOYER"));

        assertThat(fields).isEmpty();
    }

    @Test
    void shouldRejectInvalidPhone() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("12345", null, "abc12345", "小明", "WORKER"));

        assertThat(fields).containsEntry("phone", "手机号格式不合法");
    }

    @Test
    void shouldRejectInvalidEmail() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest(null, "not-an-email", "abc12345", "小明", "WORKER"));

        assertThat(fields).containsEntry("email", "邮箱格式不合法");
    }

    @Test
    void shouldRejectEmailLongerThan128() {
        String longEmail = "a" + "b".repeat(120) + "@example.com";

        Map<String, String> fields = validator.validate(
                new RegisterRequest(null, longEmail, "abc12345", "小明", "WORKER"));

        assertThat(fields).containsEntry("email", "邮箱格式不合法");
    }

    /** 空白串按空处理：手机号与邮箱都缺时两个字段都要报，前端才知道两边都能填。 */
    @Test
    void shouldRequirePhoneOrEmailWhenBothBlank() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("  ", "", "abc12345", "小明", "WORKER"));

        assertThat(fields)
                .containsEntry("phone", "手机号与邮箱至少填写一个")
                .containsEntry("email", "手机号与邮箱至少填写一个");
    }

    @Test
    void shouldRejectPasswordShorterThan8() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("13800138000", null, "abc1234", "小明", "WORKER"));

        assertThat(fields).containsEntry("password", "口令长度需 8~64 位且同时包含字母与数字");
    }

    @Test
    void shouldRejectPasswordWithoutDigit() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("13800138000", null, "abcdefgh", "小明", "WORKER"));

        assertThat(fields).containsEntry("password", "口令长度需 8~64 位且同时包含字母与数字");
    }

    @Test
    void shouldRejectPasswordWithoutLetter() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("13800138000", null, "12345678", "小明", "WORKER"));

        assertThat(fields).containsEntry("password", "口令长度需 8~64 位且同时包含字母与数字");
    }

    @Test
    void shouldRejectBlankNickname() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("13800138000", null, "abc12345", "   ", "WORKER"));

        assertThat(fields).containsEntry("nickname", "昵称不能为空");
    }

    @Test
    void shouldRejectNicknameLongerThan64() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("13800138000", null, "abc12345", "a".repeat(65), "WORKER"));

        assertThat(fields).containsEntry("nickname", "昵称长度不能超过 64");
    }

    /** ADMIN 只能由后台开通，注册口一律拒绝（docs/decisions.md #20）。 */
    @Test
    void shouldRejectAdminRole() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("13800138000", null, "abc12345", "小明", "ADMIN"));

        assertThat(fields).containsEntry("role", "role 只允许 EMPLOYER 或 WORKER");
    }

    /** 大小写敏感：小写 worker 同样拒绝，避免前后端对「枚举名」的理解出现两个版本。 */
    @Test
    void shouldRejectLowercaseRole() {
        Map<String, String> fields = validator.validate(
                new RegisterRequest("13800138000", null, "abc12345", "小明", "worker"));

        assertThat(fields).containsEntry("role", "role 只允许 EMPLOYER 或 WORKER");
    }
}
