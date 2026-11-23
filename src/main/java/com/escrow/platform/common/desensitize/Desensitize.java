package com.escrow.platform.common.desensitize;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 响应字段脱敏标记（v1-c6）：两层脱敏中的第二层——序列化层。
 *
 * <p>用法：给 DTO 的字段（或 record 组件）加上本注解并指定 {@link Type}，
 * Jackson 序列化时改走 {@link DesensitizeSerializer}，由它按「当前会话是否管理员」
 * 决定原样输出还是打码（NFR-SEC.03：管理级可见、非当事人隐藏联系方式）。</p>
 *
 * <p>三个元注解缺一不可：</p>
 * <ul>
 *   <li>{@code @JacksonAnnotationsInside}：让 Jackson 把本注解当成「一组 Jackson 注解的包装」，
 *       否则写在字段上的自定义注解对 Jackson 完全不可见；</li>
 *   <li>{@code @JsonSerialize(using = ...)}：真正指定序列化器；</li>
 *   <li>{@code @Retention(RUNTIME)}：运行时反射读取，缺了它 Jackson 拿不到注解。</li>
 * </ul>
 *
 * <p>{@code @Target({FIELD, METHOD})}：record 组件上的注解会按此自动传播到字段与访问器方法，
 * 因此 record DTO 不需要额外写法。</p>
 *
 * @since V1
 */
@JacksonAnnotationsInside
@JsonSerialize(using = DesensitizeSerializer.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Desensitize {

    /**
     * 脱敏类型，决定打码后保留哪几位。
     *
     * @return 该字段的脱敏类型
     */
    Type value();

    /** 脱敏类型。 */
    enum Type {

        /** 手机号：{@code 138****5678}。 */
        PHONE,

        /** 邮箱：{@code a***@example.com}。 */
        EMAIL,

        /** 证件号：{@code 110***********1234}。 */
        ID_CARD,

        /** 银行卡号：{@code 6222********1234}。 */
        BANK_CARD
    }
}
