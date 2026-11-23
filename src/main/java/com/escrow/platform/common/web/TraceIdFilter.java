package com.escrow.platform.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路号过滤器（依据 docs/decisions.md #29）。
 *
 * <p>读请求头 {@code X-Trace-Id}；没有就生成 32 位 UUID（去横线）。写入 MDC 供日志使用，
 * 并回写到响应头，保证「报错时把 traceId 报给用户，用它在日志里 grep 到那一行」。
 * V5 接 SkyWalking 时其 traceId 也放 {@code MDC.traceId}，契约不用改。</p>
 *
 * @since V0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 链路号请求 / 响应头。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** MDC 中的链路号键（与 V5 SkyWalking 的键保持一致）。 */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId) || traceId.length() > 64 || !traceId.matches("[0-9a-zA-Z._-]+")) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程池复用线程：不清会把本次 traceId 串到下一个请求的日志上
            MDC.clear();
        }
    }
}
