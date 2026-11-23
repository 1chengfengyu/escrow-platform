package com.escrow.platform.identity.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.application.QualificationService;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 雇主资质自助入口（FR-ACCT.QUAL.01~QUAL.03）。
 *
 * <p>Controller 只做三件事：取会话主体、转交 application 层、包 {@link ApiResponse}。
 * 「是否雇主身份」「字段是否合法」「首次还是重复提交」全部在
 * {@link QualificationService}，避免这些规则被第二个入口（如将来的管理端代提交）复制一份。</p>
 *
 * <p>两个接口都不在 {@code SecurityConfig.PUBLIC_PATHS} 里：未登录一律 401，
 * 不需要在这里判空。</p>
 *
 * @since V1
 */
@RestController
@RequestMapping("/api/identity/qualification")
@RequiredArgsConstructor
public class QualificationController {

    private final QualificationService qualificationService;

    /**
     * 提交 / 重新提交资质，成功返回 200 + {@code data=null}。
     *
     * <p>没有成功载荷：提交只产生一个「待审核」状态，客户端用 {@code GET} 查最新状态即可。</p>
     */
    @PostMapping
    public ApiResponse<Void> submit(@RequestBody QualificationSubmitRequest request,
                                    @AuthenticationPrincipal LoginUser current) {
        qualificationService.submit(current, request);
        return ApiResponse.ok(null);
    }

    /** 查询当前账号的资质；从未提交返回 {@code status=UNSUBMITTED}（不返回 404）。 */
    @GetMapping
    public ApiResponse<QualificationView> get(@AuthenticationPrincipal LoginUser current) {
        return ApiResponse.ok(qualificationService.get(current));
    }
}
