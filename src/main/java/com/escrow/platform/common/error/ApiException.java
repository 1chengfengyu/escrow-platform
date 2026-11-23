package com.escrow.platform.common.error;

import lombok.Getter;

/**
 * 可预期的业务异常：携带协议码与可选的结构化数据。
 *
 * <p>由业务层主动抛出，{@code GlobalExceptionHandler} 统一转成 {@code ApiResponse}：
 * HTTP 状态取 {@link ErrorCode#getHttpStatus()}，{@code data} 原样透出
 * （注册字段错误用 {@code data.fields} 指明具体字段，FR-ACCT.REG.01）。</p>
 *
 * <p>刻意不覆盖 {@code fillInStackTrace}、不做静态工厂：调用点只有两种构造形态，
 * 套一层工厂只会让「错误码 + 文案 + 数据」的对应关系多绕一道。</p>
 *
 * @since V1
 */
@Getter
public class ApiException extends RuntimeException {

    /** 协议码，决定 HTTP 状态与 body.code。 */
    private final ErrorCode errorCode;

    /** 结构化附加数据，可为 {@code null}（如 {@code Map.of("fields", fields)}）。 */
    private final Object data;

    /** 无附加数据的业务异常。 */
    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /** 带结构化数据的业务异常。 */
    public ApiException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }
}
