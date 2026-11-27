package com.escrow.platform.common.web;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.application.SecurityEventRecorder;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 全局异常处理器（依据 docs/decisions.md #30 / #31 / #32）。
 *
 * <p>原则：HTTP 状态码语义化（不用「永远 200」），{@code body.code} 承载业务细分；
 * 500 只返回固定文案，堆栈只进日志（SRS IR-UI.02：不暴露堆栈或 SQL）。</p>
 *
 * @since V0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 可选依赖：切片测试不加载 Mapper 时不存在，此时只返回 403，不落安全事件。
     */
    @Autowired(required = false)
    private SecurityEventRecorder securityEventRecorder;

    private static final String GENERIC_VALIDATION_MESSAGE = "请求参数不合法";
    private static final String GENERIC_INTERNAL_MESSAGE = "系统繁忙，请稍后重试";
    private static final String UNREADABLE_MESSAGE = "请求体格式不正确";

    // V0 的 pom 没有 spring-security（security=0），不能 import 这两个类型，
    // 故按全限定类名字符串匹配；V1 引入 Security 后可改为直接引用类型。
    private static final String ACCESS_DENIED_CLASS =
            "org.springframework.security.access.AccessDeniedException";
    private static final String AUTHENTICATION_CLASS =
            "org.springframework.security.core.AuthenticationException";

    /**
     * 业务异常：HTTP 状态与 {@code code} 都取自 {@link ErrorCode}，{@code data} 原样透出。
     *
     * <p>注册字段错误（{@code INVALID_PARAMETER}）的 {@code data.fields} 靠这条通道到达前端；
     * 不并进通用兜底，否则会被改写成 {@code INTERNAL_ERROR} 或 {@code VALIDATION_FAILED}。</p>
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApiException(ApiException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        if (errorCode == ErrorCode.FORBIDDEN) {
            recordDenied(ex.getMessage());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(errorCode.getHttpStatus());
        if (errorCode == ErrorCode.RATE_LIMITED) {
            // 429 必须带 Retry-After（docs/decisions.md #31）：客户端按秒退避，否则会立刻重试把限流窗口打满
            Long retryAfter = retryAfterSeconds(ex.getData());
            if (retryAfter != null) {
                builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            }
        }
        return builder.body(ApiResponse.fail(errorCode, ex.getMessage(), ex.getData()));
    }

    /** 参数校验失败：{@link BindException} 覆盖其子类 MethodArgumentNotValidException。 */
    @ExceptionHandler({BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(GENERIC_VALIDATION_MESSAGE);
        return build(ErrorCode.VALIDATION_FAILED, message);
    }

    /**
     * 方法参数校验失败：Spring 6.1+ 下 {@code @RequestParam @NotBlank} 走这条（#32 要点 4）。
     */
    @ExceptionHandler({HandlerMethodValidationException.class})
    public ResponseEntity<ApiResponse<Void>> handleMethodValidationException(
            HandlerMethodValidationException ex) {
        String message = ex.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> {
                            String parameterName = result.getMethodParameter().getParameterName();
                            String text = error.getDefaultMessage();
                            return parameterName == null || parameterName.isBlank() ? text : parameterName + " " + text;
                        }))
                .findFirst()
                .orElse(GENERIC_VALIDATION_MESSAGE);
        return build(ErrorCode.VALIDATION_FAILED, message);
    }

    /** 类级 {@code @Validated} 的方法校验失败（非控制器路径也会走到这里）。 */
    @ExceptionHandler({ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> {
                    String path = violation.getPropertyPath().toString();
                    return path.substring(path.lastIndexOf('.') + 1) + " " + violation.getMessage();
                })
                .orElse(GENERIC_VALIDATION_MESSAGE);
        return build(ErrorCode.VALIDATION_FAILED, message);
    }

    /** 路径/查询参数类型不匹配（如会话 ID 传了非数字）：400，不能落到 500 触发客户端重试。 */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(
            MethodArgumentTypeMismatchException ex) {
        return build(ErrorCode.VALIDATION_FAILED, GENERIC_VALIDATION_MESSAGE);
    }

    /** JSON 解析失败（请求体不是合法 JSON、类型不匹配）。 */
    @ExceptionHandler({HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex) {
        return build(ErrorCode.VALIDATION_FAILED, UNREADABLE_MESSAGE);
    }

    /** 路径不存在：{@link NoResourceFoundException}（Boot 3.2+）与 {@link NoHandlerFoundException} 都要接，否则 404 漏成 Whitelabel JSON。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(Exception ex) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.getDefaultMessage());
    }

    /** 请求方法不被支持（GET 打了 POST 接口等）。 */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage());
    }

    /**
     * 并发冲突：乐观锁版本冲突、死锁回滚、锁等待超时（FR-ESCROW.HOLD.04 ②，requirements.md:465）。
     *
     * <p>这条必须排在 {@link DataAccessException} 之前被选中——Spring 按最具体的异常类型匹配，
     * {@link ConcurrencyFailureException} 比 {@code DataAccessException} 具体，所以会优先命中。</p>
     *
     * <p>归 409 而不是 503：并发冲突是两笔业务撞在一起，不是依赖故障。需求 ② 同时写明
     * 客户端可自动重试 1 次；若沿用 503，网关与客户端会按「暂时不可用」无控重试。</p>
     *
     * <p>日志用 warn 不用 error：并发冲突不是故障，用 error 会让告警噪音掩盖真正的依赖不可用。</p>
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrencyConflict(ConcurrencyFailureException ex) {
        log.warn("并发冲突 traceId={} type={}", currentTraceId(), ex.getClass().getSimpleName());
        return build(ErrorCode.CONCURRENT_MODIFICATION, ErrorCode.CONCURRENT_MODIFICATION.getDefaultMessage());
    }

    /** 依赖不可用：DataAccessException 覆盖 DB 连不上与 Redis 连不上（父链到 DataAccessResourceFailureException）。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDependencyException(Exception ex) {
        log.error("依赖不可用 traceId={}", currentTraceId(), ex);
        return build(ErrorCode.DEPENDENCY_UNAVAILABLE, ErrorCode.DEPENDENCY_UNAVAILABLE.getDefaultMessage());
    }

    /** 其余未预期异常：固定文案兜底，响应体不含堆栈（#32 要点 2）。 */
    @ExceptionHandler({Exception.class})
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        if (matchesTypeName(ex, ACCESS_DENIED_CLASS)) {
            recordDenied(ex.getMessage());
            return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage());
        }
        if (matchesTypeName(ex, AUTHENTICATION_CLASS)) {
            return build(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.getDefaultMessage());
        }
        // Spring 自带的客户端错误（缺参 / 类型不匹配 / 415）必须留在 4xx，
        // 兜成 500 会触发客户端按 5xx 自动重试（#31 第 2 条）；5xx 的 ErrorResponse 落回下面的 500
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            ErrorCode code = errorResponse.getStatusCode().value() == 404
                    ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.VALIDATION_FAILED;
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(ApiResponse.fail(code, code.getDefaultMessage()));
        }
        log.error("未预期异常 traceId={}", currentTraceId(), ex);
        return build(ErrorCode.INTERNAL_ERROR, GENERIC_INTERNAL_MESSAGE);
    }

    private void recordDenied(String reason) {
        if (securityEventRecorder != null) {
            // 业务层数据权限拒绝也计入越权告警（NFR-SEC.08）
            securityEventRecorder.recordDenied(reason);
        }
    }

    private static ResponseEntity<ApiResponse<Void>> build(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.fail(errorCode, message));
    }

    /** 从 {@code data.retryAfter} 取建议重试秒数；缺字段或类型不对时返回 {@code null}（不写响应头）。 */
    private static Long retryAfterSeconds(Object data) {
        if (data instanceof Map<?, ?> map && map.get("retryAfter") instanceof Number seconds) {
            return seconds.longValue();
        }
        return null;
    }

    /** 沿父类链比较全限定类名，兼容子类（如 BadCredentialsException 之于 AuthenticationException）。 */
    private static boolean matchesTypeName(Throwable ex, String typeName) {
        for (Class<?> type = ex.getClass(); type != null; type = type.getSuperclass()) {
            if (typeName.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null ? "" : traceId;
    }
}
