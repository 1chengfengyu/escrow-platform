package com.escrow.platform.common.security.application;

import com.escrow.platform.common.security.domain.SecurityEvent;
import com.escrow.platform.common.security.infrastructure.mapper.SecurityEventMapper;
import com.escrow.platform.identity.domain.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 安全事件记录器（V7，NFR-SEC.08）。
 *
 * <p>越权告警是旁路：记录失败只能吞掉并打 warn，绝不能再抛异常覆盖原始 403 / 401。
 * 因此这里不使用 {@code @Transactional}，也不允许调用方把异常往外抛。</p>
 *
 * @since V7
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityEventRecorder {

    private static final int MAX_PATH = 255;
    private static final int MAX_REASON = 500;
    private static final String UNKNOWN = "UNKNOWN";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final SecurityEventMapper securityEventMapper;

    /**
     * 记录一次拒绝访问。
     *
     * @param reason 拒绝原因；超长自动截断
     */
    public void recordDenied(String reason) {
        recordDenied(reason, null, null);
    }

    /**
     * 记录一次拒绝访问（带目标资源）。
     *
     * @param reason     拒绝原因
     * @param targetType 目标资源类型，可空
     * @param targetId   目标资源 ID，可空
     */
    public void recordDenied(String reason, String targetType, Long targetId) {
        try {
            HttpServletRequest request = currentRequest();
            LoginUser user = currentUser();
            SecurityEvent event = new SecurityEvent();
            event.setUserId(user == null ? null : user.userId());
            event.setRole(user == null ? null : user.currentIdentity().name());
            event.setMethod(request == null ? UNKNOWN : request.getMethod());
            event.setPath(truncate(request == null ? UNKNOWN : request.getRequestURI(), MAX_PATH));
            event.setReason(truncate(reason == null ? "FORBIDDEN" : reason, MAX_REASON));
            event.setTargetType(truncate(targetType, 32));
            event.setTargetId(targetId);
            event.setIp(request == null ? null : clientIp(request));
            event.setCreatedAt(LocalDateTime.now());
            securityEventMapper.insert(event);
        } catch (RuntimeException ex) {
            log.warn("安全事件记录失败 reason={}", reason, ex);
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma < 0 ? forwarded : forwarded.substring(0, comma);
            return truncate(first.trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
