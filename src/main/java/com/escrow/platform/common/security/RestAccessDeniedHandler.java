package com.escrow.platform.common.security;

import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.application.SecurityEventRecorder;
import com.escrow.platform.common.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 403 统一出口（v1-c1，依据 docs/decisions.md #29 / #31）。
 *
 * <p>与 401 的区别：401 是「你是谁我不认识」，403 是「我认识你，但你不许动这个资源」。
 * 两者必须用不同的 HTTP 码与 {@code code}，否则前端无法决定是「去登录」还是「别试了」。</p>
 *
 * @since V1
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * 可选依赖：切片测试（{@code @WebMvcTest}）不加载 Mapper 时该 Bean 不存在，
     * 此时只返回 403，不做安全事件落库。
     */
    @Autowired(required = false)
    private SecurityEventRecorder securityEventRecorder;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        // 越权告警旁路：记录失败不得影响 403 响应本身
        if (securityEventRecorder != null) {
            securityEventRecorder.recordDenied(accessDeniedException.getMessage());
        }
        write(response, ApiResponse.fail(ErrorCode.FORBIDDEN));
    }

    private void write(HttpServletResponse response, ApiResponse<Void> body) throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
