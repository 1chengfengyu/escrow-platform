package com.escrow.platform.identity.domain;

import java.io.Serializable;
import java.security.Principal;

/**
 * 登录态主体（v1-c4）：会话里承载的 {@code Authentication#getPrincipal()}。
 *
 * <p>为什么放 identity 而不是 common：common 是最底层，identity 依赖 common；
 * 把业务主体放进 common 会让 common 反过来知道账号域，违反 project_rules 的层间规则，
 * V8 拆服务时这一行 import 就是反向依赖。</p>
 *
 * <p>为什么 {@code defaultRole} 与 {@code currentIdentity} 并存：前者是注册时选的默认视图，
 * 后者是本次会话正在使用的身份（FR-ACCT.ROLE.03 允许同会话切换）。
 * 切换只改 {@code currentIdentity}，不动 {@code defaultRole}，否则「切回来」就没有依据。</p>
 *
 * <p>实现 {@link Principal}：让 {@code Authentication#getName()} 返回账号本身而不是 record 的
 * {@code toString()}，c1 的 {@code /api/sec/whoami} 语义因此保持不变。</p>
 *
 * <p>实现 {@link Serializable}：Spring Session 默认用 JDK 序列化把会话写进 Redis，
 * 主体不可序列化会在登录成功那一刻抛 {@code NotSerializableException}。</p>
 *
 * @param userId          账号 ID
 * @param account         登录时输入的手机号或邮箱（trim 后）
 * @param defaultRole     注册时选择的默认身份
 * @param currentIdentity 当前会话正在使用的身份
 * @since V1
 */
public record LoginUser(Long userId, String account, UserRole defaultRole, IdentityType currentIdentity)
        implements Principal, Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return account;
    }
}
