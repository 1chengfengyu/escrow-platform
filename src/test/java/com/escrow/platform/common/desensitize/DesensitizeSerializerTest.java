package com.escrow.platform.common.desensitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应脱敏序列化器单测（v1-c6 §8 第 2 条，NFR-SEC.03）。
 *
 * <p>用 record 承载字段而不是普通类：生产里的载体 {@code UserCardResponse} 就是 record，
 * 这里顺带验证「record 组件上的 {@code @Desensitize} 能被 Jackson 读到」这条前提。
 * record 组件上的注解会按 {@code @Target({FIELD, METHOD})} 自动传播到字段与访问器。</p>
 *
 * <p>角色靠手工装配 {@link SecurityContextHolder}，不引 {@code spring-security-test}
 * （docs/decisions.md #40：只引 spring-boot-starter-test）。</p>
 *
 * @since V1
 */
class DesensitizeSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private record PhoneHolder(@Desensitize(Desensitize.Type.PHONE) String value) {
    }

    private record EmailHolder(@Desensitize(Desensitize.Type.EMAIL) String value) {
    }

    private record IdCardHolder(@Desensitize(Desensitize.Type.ID_CARD) String value) {
    }

    private record BankCardHolder(@Desensitize(Desensitize.Type.BANK_CARD) String value) {
    }

    /** 手机号：普通视角打码为 {@code 138****5678}。 */
    @Test
    void phoneShouldBeMaskedForNonAdmin() throws Exception {
        assertThat(objectMapper.writeValueAsString(new PhoneHolder("13800138000")))
                .isEqualTo("{\"value\":\"138****8000\"}");
    }

    /** 邮箱：保留首字符与域名。 */
    @Test
    void emailShouldKeepFirstCharAndDomain() throws Exception {
        assertThat(objectMapper.writeValueAsString(new EmailHolder("boss@example.com")))
                .isEqualTo("{\"value\":\"b***@example.com\"}");
    }

    /** 证件号：留前 3 后 4。 */
    @Test
    void idCardShouldKeepHeadAndTail() throws Exception {
        assertThat(objectMapper.writeValueAsString(new IdCardHolder("110101199001011234")))
                .isEqualTo("{\"value\":\"110***********1234\"}");
    }

    /** 银行卡：留前 4 后 4。 */
    @Test
    void bankCardShouldKeepHeadAndTail() throws Exception {
        assertThat(objectMapper.writeValueAsString(new BankCardHolder("6222021234567890")))
                .isEqualTo("{\"value\":\"6222********7890\"}");
    }

    /** 管理员：原样输出（NFR-SEC.03 管理级可见）。 */
    @Test
    void adminShouldSeeRawValue() throws Exception {
        loginAsAdmin();

        assertThat(objectMapper.writeValueAsString(new PhoneHolder("13800138000")))
                .isEqualTo("{\"value\":\"13800138000\"}");
    }

    /** 值过短：整体替换为 {@code ***}，不抛异常、不越界。 */
    @Test
    void shortValueShouldBeFullyMasked() throws Exception {
        assertThat(objectMapper.writeValueAsString(new PhoneHolder("123")))
                .isEqualTo("{\"value\":\"***\"}");
    }

    private static void loginAsAdmin() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
    }
}
