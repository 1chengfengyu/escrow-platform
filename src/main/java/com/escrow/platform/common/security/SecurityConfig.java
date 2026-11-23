package com.escrow.platform.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全基线过滤器链（v1-c1，依据 docs/decisions.md #31 与 docs/03 V1 段）。
 *
 * <p>本卡只做「骨架 + 401/403 契约」：认证方式（表单登录 / 注册登录接口）留给后续批次，
 * 因此这里只保留「默认拒绝、白名单放行」的行为，不写 {@code sessionManagement(STATELESS)}
 * ——V1 是会话认证，写成无状态等于提前否掉 c4 的会话进 Redis。</p>
 *
 * <p>放行清单里的 {@code /error} 是 Spring 的错误分发路径；不放行会把 4xx/5xx 二次转发拦成 401，
 * 导致真实错误码永远看不到。</p>
 *
 * @since V1
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** BCrypt 强度：cost 10（依据 docs/03 V1 段「BCrypt 密码哈希 cost 10」）。 */
    private static final int PASSWORD_COST = 10;

    /**
     * 无需认证即可访问的通路：健康探针、V0 自检口、Spring 错误分发。
     *
     * <p>{@code /api/ping/**} 必须放行整棵子树：docs/decisions.md #32 的三条演示通路
     * （{@code /api/ping} 200、{@code /api/ping/echo} 400、{@code /api/ping/boom} 5xx）
     * 有一条被拦成 401，V0 的异常链路演示就断了。</p>
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/ping/**",
            // 注册是访客入口（FR-ACCT.REG.01）：只放行这一条，不整段放行 /api/identity/**，
            // 否则 c4 的登录限流与 whoami 的鉴权都会被一起放过
            "/api/identity/register",
            // 登录是访客入口（FR-ACCT.LOGIN.01）：同样只放行这一条；
            // /me、/switch 一律受保护，否则「无 Cookie 401」这条演示不成立
            "/api/identity/login",
            "/actuator/health",
            "/actuator/info",
            "/error"
    };

    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    /**
     * 过滤器链：CSRF / formLogin / httpBasic / logout / requestCache 全部关闭。
     *
     * <p>关 CSRF 的原因：无前端、演示用 {@code curl}，不关则所有 POST 直接 403；
     * 关其余默认机制的原因：V1 的登录入口是自己写的 {@code /api/identity/login}，
     * 不能让 Spring 自带的表单登录 / Basic 认证先接管请求。</p>
     *
     * @param http Spring Security 的链式配置入口
     * @return 本应用唯一的过滤器链
     * @throws Exception 配置构建失败
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 无状态防护关闭：演示环境没有浏览器表单，CSRF token 反而挡住所有写接口
                .csrf(AbstractHttpConfigurer::disable)
                // 关闭 Spring 自带的四种交互式认证/登出入口，认证完全由业务接口负责
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // Spring Security 6 默认 requireExplicitSave=true：登录成功后我们只把上下文放进
                // SecurityContextHolder，不关掉这一项它就不会写回会话，下一次请求仍是 401
                .securityContext(context -> context.requireExplicitSave(false))
                // 默认拒绝：除白名单外一律要求已认证
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // 未认证走 401、无权限走 403，响应体统一为 ApiResponse 四字段
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler));
        return http.build();
    }

    /**
     * 口令哈希器：BCrypt，cost 10，显式指定 {@code $2A} 版本保证跨库可验证。
     *
     * <p>不用 MD5 的理由：BCrypt 自带随机盐（同一口令每次哈希不同）、cost 可调（抗暴力破解）、
     * 抗彩虹表。{@code $2A} 是与老系统 / 其他语言实现互通的默认版本，不写死会在升级
     * Spring Security 时跟随默认值变化。</p>
     *
     * @return 全局唯一的口令哈希器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2A, PASSWORD_COST);
    }
}
