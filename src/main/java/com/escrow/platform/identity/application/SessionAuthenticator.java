package com.escrow.platform.identity.application;

import com.escrow.platform.identity.domain.LoginUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话认证装配（v1-c4 §2.1）。
 *
 * <p>把 {@link LoginUser} 装成 {@link Authentication} 放进 {@link SecurityContextHolder}：
 * principal 是 {@code LoginUser}，authorities 由「当前身份」生成
 * （{@code ROLE_EMPLOYER} / {@code ROLE_WORKER}），后续接口按角色授权时直接读它。</p>
 *
 * <p>为什么只写 {@code SecurityContextHolder} 就够了：{@code SecurityConfig} 里把
 * {@code requireExplicitSave} 关成 {@code false}，请求结束时过滤器会自动把上下文写回会话
 * （Redis）。不关那一项，这里设置完也不会被持久化，下一次请求仍是 401。</p>
 *
 * <p>登录与切身份共用本类：两处若各写一遍 authority 前缀，等 V2 加角色授权时必有一处漏改。</p>
 *
 * @since V1
 */
@Component
public class SessionAuthenticator {

    /** Spring Security 的角色前缀，写在权限名里，不写进登录用户名。 */
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * 用给定主体覆盖当前请求的安全上下文。
     *
     * @param loginUser 会话主体（登录时是新建的，切身份时是换过 {@code currentIdentity} 的副本）
     */
    public void authenticate(LoginUser loginUser) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                loginUser,
                null,
                List.of(new SimpleGrantedAuthority(ROLE_PREFIX + loginUser.currentIdentity().name())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
