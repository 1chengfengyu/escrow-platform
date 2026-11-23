package com.escrow.platform.common.web;

import com.escrow.platform.common.error.ErrorCode;
import org.slf4j.MDC;

/**
 * 统一响应体（依据 docs/decisions.md #29）。
 *
 * <p>字段固定为 {@code code / message / data / traceId} 四个：
 * 不加 {@code success}（与 {@code code} 表达同一信息，必然出现不一致的 bug），
 * 不加 {@code timestamp}（日志里有时间，客户端本地也能取）。</p>
 *
 * <p>{@code traceId} 取自 {@link TraceIdFilter} 写入的 MDC，保证报错时能按链路号查日志。</p>
 *
 * @param <T> 成功时的载荷类型
 * @since V0
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    /** 成功文案固定为 {@code ok}。 */
    public static final String SUCCESS_MESSAGE = "ok";

    /** 成功响应：{@code code=SUCCESS}、{@code message=ok}。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), SUCCESS_MESSAGE, data, currentTraceId());
    }

    /** 失败响应：{@code data=null}，{@code code} 用字符串常量（SRS 的错误码全是字符串）。 */
    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(code, message, null, currentTraceId());
    }

    /** 失败响应（重载）：{@code code} 取自枚举，{@code message} 可覆盖。 */
    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.getCode(), message);
    }

    /** 失败响应（重载）：{@code code} 与 {@code message} 都用枚举默认值。 */
    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getDefaultMessage());
    }

    /**
     * 失败响应（重载）：{@code data} 承载结构化错误信息。
     *
     * <p>注册字段错误的约定是 {@code data.fields}（FR-ACCT.REG.01、docs/decisions.md #30），
     * 其余失败路径继续用 {@code data=null} 的两个重载。</p>
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.getCode(), message, data, currentTraceId());
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null ? "" : traceId;
    }
}
