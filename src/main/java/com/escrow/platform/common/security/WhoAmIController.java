package com.escrow.platform.common.security;

import com.escrow.platform.common.web.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 安全基线自检口（v1-c1 第 1.4 节）。
 *
 * <p>不是业务接口，只用来验证「未登录 401 / 已登录 200 + 当前用户名」这条契约，
 * V1 结束可保留：它是过滤链是否真的生效的最短验证路径。</p>
 *
 * @since V1
 */
@RestController
@RequestMapping("/api/sec")
public class WhoAmIController {

    /** 已登录返回当前用户名；未登录时根本进不了方法，由 {@link RestAuthenticationEntryPoint} 拦成 401。 */
    @GetMapping("/whoami")
    public ApiResponse<Map<String, String>> whoami(Authentication authentication) {
        return ApiResponse.ok(Map.of("username", authentication.getName()));
    }
}
