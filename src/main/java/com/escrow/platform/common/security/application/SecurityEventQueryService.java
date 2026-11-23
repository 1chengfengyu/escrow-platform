package com.escrow.platform.common.security.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.api.SecurityEventView;
import com.escrow.platform.common.security.domain.SecurityEvent;
import com.escrow.platform.common.security.infrastructure.mapper.SecurityEventMapper;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 安全事件查询（V7，NFR-SEC.08「可按账号与时间检索」）。
 *
 * @since V7
 */
@Service
@RequiredArgsConstructor
public class SecurityEventQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SecurityEventMapper securityEventMapper;

    /**
     * 分页查询安全事件。
     *
     * @param current 当前登录用户，必须是管理员
     * @param userId  账号过滤，可空
     * @param from    起始时间，含，可空
     * @param to      结束时间，含，可空
     * @param page    页码，从 1 开始
     * @param size    每页条数，1~100
     * @return 当前页事件
     */
    @Transactional(readOnly = true)
    public PageView<SecurityEventView> list(LoginUser current, Long userId, LocalDateTime from,
                                            LocalDateTime to, int page, int size) {
        requireAdmin(current);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        LambdaQueryWrapper<SecurityEvent> wrapper = new LambdaQueryWrapper<SecurityEvent>()
                .orderByDesc(SecurityEvent::getCreatedAt)
                .orderByDesc(SecurityEvent::getId);
        if (userId != null) {
            wrapper.eq(SecurityEvent::getUserId, userId);
        }
        if (from != null) {
            wrapper.ge(SecurityEvent::getCreatedAt, from);
        }
        if (to != null) {
            wrapper.le(SecurityEvent::getCreatedAt, to);
        }
        Page<SecurityEvent> result = securityEventMapper.selectPage(
                new Page<>(safePage, safeSize), wrapper);
        List<SecurityEventView> items = result.getRecords().stream()
                .map(SecurityEventQueryService::toView)
                .toList();
        return PageView.of(items, safePage, safeSize, result.getTotal());
    }

    private static SecurityEventView toView(SecurityEvent event) {
        return new SecurityEventView(event.getId(), event.getUserId(), event.getRole(),
                event.getMethod(), event.getPath(), event.getReason(), event.getTargetType(),
                event.getTargetId(), event.getIp(), event.getCreatedAt());
    }

    static void requireAdmin(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可访问");
        }
    }
}
