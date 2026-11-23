package com.escrow.platform.common.desensitize;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

/**
 * {@link Desensitize} 的序列化器（v1-c6）。
 *
 * <p>实现 {@link ContextualSerializer} 的原因：同一个序列化器实例要服务 PHONE / EMAIL /
 * ID_CARD / BANK_CARD 四种字段，而类型信息写在注解参数里。{@link #createContextual} 在
 * 「为某个具体字段创建序列化器」时被调用一次，此时能通过 {@code property} 读到
 * {@link Desensitize#value()}，于是返回一个带类型的实例。</p>
 *
 * <p>不能用 {@code ThreadLocal} 传类型：序列化器实例是全局共享的，用可变状态传参会串到其他字段；
 * 而 Jackson 的 contextual 机制本来就是为了这种「每个字段一个配置」的场景设计的。</p>
 *
 * <p>放行规则只看会话里有没有 {@code ROLE_ADMIN}（{@code SessionAuthenticator} 按当前身份生成），
 * 不做「本人豁免」——那是 V3 数据权限的事，本版只区分「管理员全量 / 其他人打码」。</p>
 *
 * @since V1
 */
public class DesensitizeSerializer extends JsonSerializer<String> implements ContextualSerializer {

    /** 值太短、无法按规则保留首尾时整体替换成它。 */
    private static final String FULLY_MASKED = "***";

    /** 管理员角色名（与 {@code SessionAuthenticator} 的前缀一致）。 */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private static final char MASK_CHAR = '*';

    /** 由注解注入的脱敏类型；{@code null} 表示未取到注解（防御分支，不参与正常流程）。 */
    private final Desensitize.Type type;

    /**
     * Jackson 通过无参构造创建初始实例（{@code @JsonSerialize(using = ...)} 的要求）。
     */
    public DesensitizeSerializer() {
        this(null);
    }

    private DesensitizeSerializer(Desensitize.Type type) {
        this.type = type;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (isAdmin()) {
            gen.writeString(value);
            return;
        }
        gen.writeString(mask(value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property) {
        if (property == null) {
            return this;
        }
        Desensitize annotation = property.getAnnotation(Desensitize.class);
        if (annotation == null) {
            return this;
        }
        return new DesensitizeSerializer(annotation.value());
    }

    /** 当前会话是否持有 {@code ROLE_ADMIN}；未认证（无 Authentication）一律按普通用户处理。 */
    private static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ROLE_ADMIN.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按类型打码；长度不足以保留规定位数时整体替换为 {@code ***}（不抛异常、不越界）。
     *
     * @param value 原始值
     * @return 打码后的值
     */
    private String mask(String value) {
        if (type == null) {
            return FULLY_MASKED;
        }
        return switch (type) {
            // 138****5678：留前 3 后 4，至少 7 位
            case PHONE -> value.length() < 7
                    ? FULLY_MASKED
                    : value.substring(0, 3) + "****" + value.substring(value.length() - 4);
            // a***@example.com：留首字符与域名，且必须有 @ 且前面还有内容
            case EMAIL -> maskEmail(value);
            // 110***********1234：留前 3 后 4，至少 8 位
            case ID_CARD -> value.length() < 8
                    ? FULLY_MASKED
                    : value.substring(0, 3) + repeatMask(value.length() - 7) + value.substring(value.length() - 4);
            // 6222********1234：留前 4 后 4，至少 9 位
            case BANK_CARD -> value.length() < 9
                    ? FULLY_MASKED
                    : value.substring(0, 4) + repeatMask(value.length() - 8) + value.substring(value.length() - 4);
        };
    }

    private static String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0) {
            return FULLY_MASKED;
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    /** 生成 n 个 {@code *}；n 一定为正（调用前已做过长度下限判断）。 */
    private static String repeatMask(int count) {
        return String.valueOf(MASK_CHAR).repeat(count);
    }
}
