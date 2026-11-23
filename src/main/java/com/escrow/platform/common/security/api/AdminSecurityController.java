package com.escrow.platform.common.security.api;

import com.escrow.platform.common.security.application.SecurityEventQueryService;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 安全事件查询（V7，NFR-SEC.08）。
 *
 * @since V7
 */
@RestController
@RequestMapping("/api/admin/security")
@RequiredArgsConstructor
public class AdminSecurityController {

    private final SecurityEventQueryService securityEventQueryService;

    /**
     * 分页查询越权 / 拒绝事件。
     *
     * @param current 当前登录用户
     * @param userId  账号过滤
     * @param from    起始时间
     * @param to      结束时间
     * @param page    页码
     * @param size    每页条数
     * @return 分页结果
     */
    @GetMapping("/events")
    public ApiResponse<PageView<SecurityEventView>> events(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(securityEventQueryService.list(current, userId, from, to, page, size));
    }
}
