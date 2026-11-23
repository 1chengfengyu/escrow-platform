package com.escrow.platform.identity.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.application.IdentityProfileService;
import com.escrow.platform.identity.application.IdentitySwitchService;
import com.escrow.platform.identity.application.LoginService;
import com.escrow.platform.identity.application.RegisterService;
import com.escrow.platform.identity.domain.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号域入口（V1：注册 / 登录 / 切身份 / 当前登录者）。
 *
 * <p>Controller 只做三件事：取调用方 IP、转交 application 层、包 {@link ApiResponse}。
 * 校验、哈希、查重、双写、限流、锁定全部在 application 层，避免「接口层写业务逻辑」被
 * 第二处调用（如将来的管理端开通账号）复制一份。</p>
 *
 * <p>IP 一律取 {@code request.getRemoteAddr()}，不读 {@code X-Forwarded-For}：
 * V1 前面没有反向代理，这个头可以被客户端任意伪造，读了等于把 IP 限流直接放开。</p>
 *
 * @since V1
 */
@RestController
@RequestMapping("/api/identity")
@RequiredArgsConstructor
public class IdentityController {

    private final RegisterService registerService;
    private final LoginService loginService;
    private final IdentitySwitchService identitySwitchService;
    private final IdentityProfileService identityProfileService;

    /** 注册，成功返回 200 + 新账号 ID（FR-ACCT.REG.01~REG.03）。 */
    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@RequestBody RegisterRequest req,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(new RegisterResponse(registerService.register(req, request.getRemoteAddr())));
    }

    /**
     * 登录，成功返回 200 + 登录者视图，会话写进 Redis（FR-ACCT.LOGIN.01）。
     *
     * <p>失败语义全部在 {@link LoginService}：429 限流 / 423 锁定 / 401 账号或口令不正确 / 403 账号禁用。</p>
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest req,
                                            HttpServletRequest request) {
        return ApiResponse.ok(loginService.login(req, request.getRemoteAddr()));
    }

    /** 会话内切换身份，只影响本条会话（FR-ACCT.ROLE.02 / ROLE.03）。 */
    @PostMapping("/switch")
    public ApiResponse<IdentitySwitchResponse> switchIdentity(@RequestBody IdentitySwitchRequest req,
                                                              @AuthenticationPrincipal LoginUser current) {
        LoginUser switched = identitySwitchService.switchIdentity(current, req.identityType());
        return ApiResponse.ok(new IdentitySwitchResponse(switched.currentIdentity().name()));
    }

    /** 当前登录者视图，用于演示「一条会话切身份、另一条不变」（v1-c4 §2.5）。 */
    @GetMapping("/me")
    public ApiResponse<IdentityMeResponse> me(@AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(identityProfileService.me(current));
    }

    /**
     * 他人名片（v1-c6 §3.3）：任意已登录用户可访问，联系方式按角色决定是否打码。
     *
     * <p>这是「响应脱敏」的演示载体：同一个 URL、同一份响应体，管理员看到完整手机号，
     * 普通用户看到 {@code 138****5678}。打码发生在 Jackson 序列化层（{@code @Desensitize}），
     * 不在本方法里，所以 V2 任务详情复用名片时不需要再写一遍规则。</p>
     *
     * @param userId 目标账号 ID
     */
    @GetMapping("/users/{userId}/card")
    public ApiResponse<UserCardResponse> userCard(@PathVariable Long userId) {
        return ApiResponse.ok(identityProfileService.card(userId));
    }
}
