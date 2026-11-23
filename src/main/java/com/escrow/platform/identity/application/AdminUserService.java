package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.audit.application.AuditRecorder;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.api.AdminUserStatusRequest;
import com.escrow.platform.identity.api.AdminUserView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 管理端账号治理（V7，FR-ADMIN.USER.01）。
 *
 * <p>放在 identity 域：它直接读写 {@code platform_user}，按「域间不调对方 Mapper」
 * 只能由账号域自己提供入口，{@code admin/api} 的控制器只做协议转换。</p>
 *
 * <p>禁用 / 启用一律「条件更新 + 校验影响行数」：两个管理员同时点禁用时，
 * 后到的那个受影响行数为 0，必须收到 409，而不是把 {@code CLOSED} 覆盖成 {@code DISABLED}。</p>
 *
 * <p>注销态（{@link UserStatus#CLOSED}）不可启用：注销是用户主动行为，
 * 管理员只能封禁与解封，不能替用户撤回注销。</p>
 *
 * @since V7
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REASON_LENGTH = 200;
    private static final String RESOURCE = "identity:user";
    private static final String ADMIN_ONLY_MESSAGE = "仅管理员可管理账号";
    private static final String NOT_FOUND_MESSAGE = "账号不存在";
    private static final String REASON_REQUIRED_MESSAGE = "必须填写变更原因";
    private static final String REASON_TOO_LONG_MESSAGE = "变更原因不能超过 " + MAX_REASON_LENGTH + " 字";
    private static final String STATUS_PARAM_MESSAGE = "status 只允许 ACTIVE / DISABLED / CLOSED";
    private static final String NOT_DISABLABLE_MESSAGE = "只有 ACTIVE 账号可以禁用";
    private static final String NOT_ENABLABLE_MESSAGE = "只有 DISABLED 账号可以启用";
    private static final String CONCURRENT_MESSAGE = "账号状态已变化，请刷新后重试";

    private final PlatformUserMapper platformUserMapper;
    private final AuditRecorder auditRecorder;
    private final SecurityLog securityLog;

    /**
     * 分页查询账号（FR-ADMIN.USER.01）。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param keyword 昵称 / 手机号 / 邮箱模糊匹配，可空
     * @param status  状态过滤，可空；只接受 ACTIVE / DISABLED / CLOSED
     * @param page    页码，从 1 开始
     * @param size    每页条数（1~{@value #MAX_PAGE_SIZE}）
     * @return 分页视图
     * @throws ApiException 403 非管理员 / 400 状态参数非法
     */
    @Transactional(readOnly = true)
    public PageView<AdminUserView> list(LoginUser current, String keyword, String status,
                                        int page, int size) {
        requireAdmin(current);
        int pageNo = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));

        LambdaQueryWrapper<PlatformUser> wrapper = new LambdaQueryWrapper<PlatformUser>()
                .orderByDesc(PlatformUser::getCreatedAt)
                .orderByDesc(PlatformUser::getId);
        String keywordValue = normalizeKeyword(keyword);
        if (keywordValue != null) {
            // 三个字段任一命中即可；括号不能省，否则 OR 会把状态条件一起绕过
            wrapper.and(nested -> nested
                    .like(PlatformUser::getNickname, keywordValue)
                    .or().like(PlatformUser::getPhone, keywordValue)
                    .or().like(PlatformUser::getEmail, keywordValue));
        }
        UserStatus filter = parseStatus(status);
        if (filter != null) {
            wrapper.eq(PlatformUser::getStatus, filter);
        }

        Page<PlatformUser> result =
                platformUserMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageView.of(result.getRecords().stream().map(AdminUserService::toView).toList(),
                pageNo, pageSize, result.getTotal());
    }

    /**
     * 禁用账号（FR-ADMIN.USER.01）：ACTIVE → DISABLED。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param userId  目标账号 ID
     * @param request 含必填原因
     * @return 变更后的账号视图
     * @throws ApiException 403 非管理员 / 400 原因不合法 / 404 账号不存在 / 409 状态不允许
     */
    @Transactional
    public AdminUserView disable(LoginUser current, Long userId, AdminUserStatusRequest request) {
        requireAdmin(current);
        String reason = requireReason(request);
        return changeStatus(current, userId, UserStatus.ACTIVE, UserStatus.DISABLED,
                "DISABLE", NOT_DISABLABLE_MESSAGE, reason);
    }

    /**
     * 启用账号（FR-ADMIN.USER.01）：DISABLED → ACTIVE。
     *
     * @param current 会话主体，必须是 ADMIN
     * @param userId  目标账号 ID
     * @param request 含必填原因
     * @return 变更后的账号视图
     * @throws ApiException 403 非管理员 / 400 原因不合法 / 404 账号不存在 / 409 状态不允许
     */
    @Transactional
    public AdminUserView enable(LoginUser current, Long userId, AdminUserStatusRequest request) {
        requireAdmin(current);
        String reason = requireReason(request);
        return changeStatus(current, userId, UserStatus.DISABLED, UserStatus.ACTIVE,
                "ENABLE", NOT_ENABLABLE_MESSAGE, reason);
    }

    /**
     * 账号总数（治理看板用，只读）。
     *
     * @return {@code platform_user} 行数
     */
    public long countAll() {
        Long count = platformUserMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    /**
     * 条件更新状态：{@code WHERE id=? AND status=from}，受影响行数为 0 即状态已被他人改过。
     */
    private AdminUserView changeStatus(LoginUser current, Long userId, UserStatus from,
                                       UserStatus to, String action, String notAllowedMessage,
                                       String reason) {
        PlatformUser existing = platformUserMapper.selectById(userId);
        if (existing == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (existing.getStatus() != from) {
            throw new ApiException(ErrorCode.CONFLICT, notAllowedMessage);
        }

        int updated = platformUserMapper.update(null, new LambdaUpdateWrapper<PlatformUser>()
                .eq(PlatformUser::getId, userId)
                .eq(PlatformUser::getStatus, from)
                .set(PlatformUser::getStatus, to));
        if (updated != 1) {
            throw new ApiException(ErrorCode.CONFLICT, CONCURRENT_MESSAGE);
        }

        securityLog.log(SecurityLog.ACTION_USER_STATUS, RESOURCE,
                existing.getPhone() == null ? existing.getEmail() : existing.getPhone(),
                action + ":" + current.userId());
        auditRecorder.record("USER", String.valueOf(userId), action,
                from.name(), to.name(), reason);
        log.info("账号状态变更 bizId={} action={} from={} to={} operator={}",
                userId, action, from, to, current.userId());

        return toView(platformUserMapper.selectById(userId));
    }

    private static void requireAdmin(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, ADMIN_ONLY_MESSAGE);
        }
    }

    private static String requireReason(AdminUserStatusRequest request) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, REASON_REQUIRED_MESSAGE);
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, REASON_TOO_LONG_MESSAGE);
        }
        return trimmed;
    }

    private static UserStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, STATUS_PARAM_MESSAGE);
        }
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        // 转义 LIKE 通配符，避免用户输入的 % / _ 把检索范围放大成全表
        return keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static AdminUserView toView(PlatformUser user) {
        return new AdminUserView(user.getId(), user.getNickname(), user.getPhone(), user.getEmail(),
                user.getRole().name(), user.getStatus().name(), user.getCreatedAt());
    }
}
