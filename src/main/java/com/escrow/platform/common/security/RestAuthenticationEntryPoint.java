package com.escrow.platform.common.security;

import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 统一出口（v1-c1，依据 docs/decisions.md #29 / #31）。
 *
 * <p>Spring Security 默认的 401 是空 body，前端只能猜；这里改为 V0 的
 * {@link ApiResponse} 四字段，{@code code=UNAUTHENTICATED}。禁止手拼 JSON——
 * 手拼的字段名会跟着 V2 加字段而漂移。</p>
 *
 * <p>{@code traceId} 依赖 {@link com.escrow.platform.common.web.TraceIdFilter} 先把链路号写进 MDC：
 * 该过滤器是 {@code HIGHEST_PRECEDENCE}，排在 Security 过滤器链之前，因此这里取到的必定非空。</p>
 *
 * @since V1
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, ApiResponse.fail(ErrorCode.UNAUTHENTICATED));
    }

    private void write(HttpServletResponse response, ApiResponse<Void> body) throws IOException {
        response.setStatus(ErrorCode.UNAUTHENTICATED.getHttpStatus());
        // 显式带 charset：curl 与浏览器都按 UTF-8 解析，中文文案不会乱码
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
