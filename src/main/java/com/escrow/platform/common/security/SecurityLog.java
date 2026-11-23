package com.escrow.platform.common.security;

import com.escrow.platform.common.web.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 结构化安全日志（v1-c4，docs/03 V1 演示第 3 条）。
 *
 * <p>固定一行五字段、顺序固定，便于 {@code grep SECURITY} 与日志平台按字段切分：
 * {@code SECURITY traceId={} account={} resource={} action={} result={}}。</p>
 *
 * <p>{@code account} 在本类内先做最小打码：手机号 {@code 138****5678}、邮箱 {@code a***@example.com}。
 * 全局日志脱敏在 c6 做；这里先打码是为了不让 c4 就开始往日志里写明文账号
 * （NFR-SEC.04：手机号 / 邮箱 / 口令 / 哈希一律不进日志）。</p>
 *
 * <p>不建审计表：审计属 V7（V1 只落日志）。</p>
 *
 * @since V1
 */
@Component
public class SecurityLog {

    /** 不用 Lombok {@code @Slf4j}：字段名 {@code log} 会与下面的 {@code log(...)} 方法同名，读起来容易误解。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityLog.class);

    /** 动作：登录。 */
    public static final String ACTION_LOGIN = "LOGIN";

    /** 动作：切换身份。 */
    public static final String ACTION_SWITCH_IDENTITY = "SWITCH_IDENTITY";

    /** 动作：提交雇主资质（v1-c5）。 */
    public static final String ACTION_QUALIFICATION_SUBMIT = "QUALIFICATION_SUBMIT";

    /** 动作：审核雇主资质（v1-c5）。 */
    public static final String ACTION_QUALIFICATION_REVIEW = "QUALIFICATION_REVIEW";

    /** 动作：管理端变更账号状态（V7，禁用 / 启用）。 */
    public static final String ACTION_USER_STATUS = "USER_STATUS";

    /** 结果：成功。 */
    public static final String RESULT_SUCCESS = "SUCCESS";

    /** 结果：成功（资质提交 / 审核沿用 v1-c5 约定的 OK）。 */
    public static final String RESULT_OK = "OK";

    /** 结果：非雇主身份提交资质。 */
    public static final String RESULT_DENIED_NOT_EMPLOYER = "DENIED_NOT_EMPLOYER";

    /** 结果：审核通过。 */
    public static final String RESULT_APPROVED = "APPROVED";

    /** 结果：审核驳回。 */
    public static final String RESULT_REJECTED = "REJECTED";

    /** 结果：失败（口令错 / 账号不存在，两者对外不可区分）。 */
    public static final String RESULT_FAILED = "FAILED";

    /** 结果：拒绝（账号禁用 / 身份未开通）。 */
    public static final String RESULT_DENIED = "DENIED";

    /** 结果：账号锁定。 */
    public static final String RESULT_LOCKED = "LOCKED";

    /** 结果：限流拒绝。 */
    public static final String RESULT_RATE_LIMITED = "RATE_LIMITED";

    /**
     * 记录一条安全日志。
     *
     * @param action   动作，取本类常量
     * @param resource 被访问的资源（如 {@code identity:WORKER}）
     * @param account  账号（手机号或邮箱），落日志前打码
     * @param result   结果
     */
    public void log(String action, String resource, String account, String result) {
        LOGGER.info("SECURITY traceId={} account={} resource={} action={} result={}",
                currentTraceId(), mask(account), resource, action, result);
    }

    /**
     * 最小打码：邮箱保留首字符与域名，手机号（≥11 位）保留前 3 后 4，其余一律 {@code ***}。
     *
     * @param account 原始账号
     * @return 打码后的账号；{@code null} / 空白返回空串
     */
    static String mask(String account) {
        if (account == null || account.isBlank()) {
            return "";
        }
        String value = account.trim();
        int at = value.indexOf('@');
        if (at > 0) {
            return value.charAt(0) + "***" + value.substring(at);
        }
        if (value.length() >= 11) {
            return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
        }
        if (value.length() >= 7) {
            return value.charAt(0) + "****" + value.substring(value.length() - 2);
        }
        return "***";
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null ? "" : traceId;
    }
}
