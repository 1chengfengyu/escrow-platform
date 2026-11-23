package com.escrow.platform.identity.application;

import com.escrow.platform.identity.api.RegisterRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 注册字段校验（FR-ACCT.REG.01），纯 Java、无 Spring 依赖，可直接单测。
 *
 * <p>为什么不用 {@code @Valid} + 注解：框架抛的是 {@code BindException}，会被全局处理器
 * 兜成 {@code VALIDATION_FAILED}；而 SRS 对注册口的要求是 {@code INVALID_PARAMETER}
 * 且 {@code data.fields} 指明字段（docs/decisions.md #30）。手工校验是唯一能同时满足两者的做法。</p>
 *
 * <p>口令上限 64 是刻意的：BCrypt 只取前 72 字节，超出部分会被静默截断，
 * 与其让用户以为长口令更安全，不如在校验层直接拒绝。</p>
 *
 * @since V1
 */
public class RegisterValidator {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 8~64 位且同时含字母与数字；{@code .} 不匹配换行，因此带换行的口令同样被拒。 */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");

    private static final int EMAIL_MAX_LENGTH = 128;
    private static final int NICKNAME_MAX_LENGTH = 64;

    private static final String PHONE_FORMAT_MESSAGE = "手机号格式不合法";
    private static final String EMAIL_FORMAT_MESSAGE = "邮箱格式不合法";
    private static final String PHONE_OR_EMAIL_REQUIRED_MESSAGE = "手机号与邮箱至少填写一个";
    private static final String PASSWORD_MESSAGE = "口令长度需 8~64 位且同时包含字母与数字";
    private static final String NICKNAME_REQUIRED_MESSAGE = "昵称不能为空";
    private static final String NICKNAME_TOO_LONG_MESSAGE = "昵称长度不能超过 64";
    private static final String ROLE_MESSAGE = "role 只允许 EMPLOYER 或 WORKER";

    /**
     * 校验注册字段。
     *
     * @param req 注册请求
     * @return 字段名 → 错误文案；空 Map 表示全部通过
     */
    public Map<String, String> validate(RegisterRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();

        String phone = blankToNull(req.phone());
        String email = blankToNull(req.email());

        if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
            fields.put("phone", PHONE_FORMAT_MESSAGE);
        }
        if (email != null && (email.length() > EMAIL_MAX_LENGTH
                || !EMAIL_PATTERN.matcher(email).matches())) {
            fields.put("email", EMAIL_FORMAT_MESSAGE);
        }
        // 两个字段都报：只报一个会让前端提示「改手机号」，而用户其实想用邮箱注册
        if (phone == null && email == null) {
            fields.put("phone", PHONE_OR_EMAIL_REQUIRED_MESSAGE);
            fields.put("email", PHONE_OR_EMAIL_REQUIRED_MESSAGE);
        }

        String password = req.password();
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            fields.put("password", PASSWORD_MESSAGE);
        }

        String nickname = req.nickname() == null ? "" : req.nickname().trim();
        if (nickname.isEmpty()) {
            fields.put("nickname", NICKNAME_REQUIRED_MESSAGE);
        } else if (nickname.length() > NICKNAME_MAX_LENGTH) {
            fields.put("nickname", NICKNAME_TOO_LONG_MESSAGE);
        }

        // 大小写敏感：注册流程永远不产生 ADMIN，该身份只由后台开通（docs/decisions.md #20）
        String role = req.role();
        if (!"EMPLOYER".equals(role) && !"WORKER".equals(role)) {
            fields.put("role", ROLE_MESSAGE);
        }

        return fields;
    }

    /** 空白串按「未填写」处理：DB 里存 NULL，不存空串（否则第二个空串会撞唯一键）。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
