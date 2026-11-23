package com.escrow.platform.ai.api;

import com.escrow.platform.ai.application.AiAccessGuard;
import com.escrow.platform.ai.application.AiProfileService;
import com.escrow.platform.ai.application.AiResults;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务者画像入口（V10-c3）：只写自己，原来由用户填写的字段才允许出现。
 *
 * <p>完单数与评分不接受请求参数：它们由业务派生，用户能改就等于没有评分。</p>
 *
 * @since V10
 */
@RestController
@RequestMapping("/api/ai/profile")
@RequiredArgsConstructor
public class AiProfileController {

    private final AiProfileService profileService;
    private final AiAccessGuard accessGuard;

    /** 写入/更新自己的画像（冷启动允许字段为空）。 */
    @PutMapping
    public ApiResponse<AiResults.Profile> save(@RequestBody ProfileRequest request,
                                               @AuthenticationPrincipal LoginUser current) {
        accessGuard.requireWorker(current);
        return ApiResponse.ok(profileService.upsert(current, new AiResults.ProfileCommand(
                request.skills(), request.experienceYears(), request.expectedUnitPrice(),
                request.location(), request.weekendAvailable())));
    }

    /** 读自己的画像。 */
    @GetMapping("/me")
    public ApiResponse<AiResults.Profile> me(@AuthenticationPrincipal LoginUser current) {
        accessGuard.requireWorker(current);
        return ApiResponse.ok(profileService.me(current));
    }

    /** 画像更新请求。 */
    public record ProfileRequest(List<String> skills, Integer experienceYears, Long expectedUnitPrice,
                                 String location, Boolean weekendAvailable) {

        public ProfileRequest {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }
}
