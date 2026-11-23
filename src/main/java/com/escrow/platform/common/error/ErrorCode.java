package com.escrow.platform.common.error;

/**
 * 协议级错误码（V0 九条，依据 docs/decisions.md #30 与 #31）。
 *
 * <p>业务错误码（如 {@code INSUFFICIENT_BALANCE}）V2 起加入本枚举，
 * 且必须与 SRS 逐字一致；禁止用 {@link #CONFLICT} / {@link #INTERNAL_ERROR} 兜底业务失败。</p>
 *
 * @since V0
 */
public enum ErrorCode {

    /** 成功。 */
    SUCCESS("SUCCESS", 200, "成功"),

    /** 参数校验失败、JSON 解析失败（400）。 */
    VALIDATION_FAILED("VALIDATION_FAILED", 400, "请求参数不合法"),

    /** 未登录 / token 失效（401，V1 启用）。 */
    UNAUTHENTICATED("UNAUTHENTICATED", 401, "未认证或登录已失效"),

    /** 已登录但无权限（403，V1 启用）。 */
    FORBIDDEN("FORBIDDEN", 403, "无权访问该资源"),

    /** 当前身份未开通或已禁用（403，V1 启用；FR-ACCT.ROLE.02）。 */
    IDENTITY_NOT_AVAILABLE("IDENTITY_NOT_AVAILABLE", 403, "当前身份不可用"),

    /** 注册字段不合法（400，FR-ACCT.REG.01）：必须在 data.fields 指明出错字段。 */
    INVALID_PARAMETER("INVALID_PARAMETER", 400, "请求参数不合法"),

    /** 手机号或邮箱已被注册（409，FR-ACCT.REG.02）。 */
    ACCOUNT_EXISTS("ACCOUNT_EXISTS", 409, "手机号或邮箱已被注册"),

    /** 任务进入招募中后修改单价或名额数（409，FR-TASK.CREATE.05）。 */
    TASK_PRICE_LOCKED("TASK_PRICE_LOCKED", 409, "任务已发布，单价与名额数不可修改"),

    /** 任务存在非终态名额时撤单（409，FR-TASK.CANCEL.02）：与 TASK_NOT_CANCELABLE 不得混用。 */
    TASK_HAS_ACTIVE_SLOT("TASK_HAS_ACTIVE_SLOT", 409, "任务存在进行中的名额，不可撤单"),

    /** 草稿 / 零名额 / 全部名额已终态时撤单（409，FR-TASK.CANCEL.02）。 */
    TASK_NOT_CANCELABLE("TASK_NOT_CANCELABLE", 409, "当前任务不可撤单"),

    /** 剩余名额为 0 时报名（409，FR-SIGN.APPLY.02）。 */
    NO_SLOT_AVAILABLE("NO_SLOT_AVAILABLE", 409, "剩余名额不足，无法报名"),

    /** 同一用户重复报名同一任务（409，FR-SIGN.APPLY.03）。 */
    ALREADY_APPLIED("ALREADY_APPLIED", 409, "已报名该任务，请勿重复提交"),

    /** 劳动者报名自己发布的任务（409，FR-SIGN.APPLY.05 / CR-15）。 */
    SELF_APPLICATION_FORBIDDEN("SELF_APPLICATION_FORBIDDEN", 409, "不能报名自己发布的任务"),

    /** 录用时名额已被并发抢占（409，FR-SIGN.HIRE.03）：并发录用只允许一个成功。 */
    SLOT_TAKEN("SLOT_TAKEN", 409, "名额已被占用，请刷新后重试"),

    /** 录用对象为任务发布者本人（409，FR-SIGN.HIRE.04 / CR-15）。 */
    SELF_HIRE_FORBIDDEN("SELF_HIRE_FORBIDDEN", 409, "不能录用自己发布的报名"),

    /** 可用余额不足（409，FR-ESCROW.HOLD.02 / FR-WALLET.WITHDRAW.02）：客户端不应重试。 */
    INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE", 409, "可用余额不足"),

    /** 乐观锁版本冲突或死锁回滚（409，FR-ESCROW.HOLD.04）：客户端可自动重试 1 次。 */
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", 409, "操作冲突，请稍后重试"),

    /** 终态实体再次执行资金操作（409，FR-ESCROW.GUARD.01 / FR-REFUND.GUARD.01）。 */
    INVALID_STATE_TRANSITION("INVALID_STATE_TRANSITION", 409, "当前状态不允许该操作"),

    /** 名额处于待裁决（争议处理中）时拒绝验收（409，FR-ACCEPT.GUARD.01）。 */
    DISPUTE_ACTIVE("DISPUTE_ACTIVE", 409, "争议处理中，暂不可验收"),

    /** 打款单处于未知 / 待人工处理，阻塞该结算单的新打款（409，FR-PAY.UNKNOWN.03）。 */
    PAYOUT_PENDING_VERIFICATION("PAYOUT_PENDING_VERIFICATION", 409, "打款结果待查证，暂不可重复发起"),

    /** 充值触达单笔限额（409，FR-WALLET.RECHARGE.02）。 */
    RECHARGE_LIMIT_EXCEEDED("RECHARGE_LIMIT_EXCEEDED", 409, "充值金额超过单笔限额"),

    /** 账号锁定（423，FR-ACCT.LOGIN.02）：连续 5 次口令错误后锁 15 分钟。 */
    ACCOUNT_LOCKED("ACCOUNT_LOCKED", 423, "账号已锁定，请稍后重试"),

    /** 触发限流（429，NFR-SEC.06）：data.retryAfter 给出建议重试秒数，并写 Retry-After 响应头。 */
    RATE_LIMITED("RATE_LIMITED", 429, "请求过于频繁，请稍后重试"),

    /** 路径或资源不存在（404）。 */
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", 404, "请求的资源不存在"),

    /** 请求方法不被支持（405）。 */
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", 405, "请求方法不被支持"),

    /** 通用冲突（409）：唯一键、乐观锁；业务能分清时必须用具体业务码。 */
    CONFLICT("CONFLICT", 409, "请求存在冲突"),

    /** 未预期异常（500）。 */
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "系统繁忙，请稍后重试"),

    /** 依赖不可用：DB / Redis 连不上（503）。 */
    DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE", 503, "依赖服务不可用，请稍后重试");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
