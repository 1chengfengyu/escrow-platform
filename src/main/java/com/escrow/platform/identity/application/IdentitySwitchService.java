package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.security.SecurityLog;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 会话内切换身份（FR-ACCT.ROLE.02 / ROLE.03，v1-c4 §2.4）。
 *
 * <p>只改当前会话：不写 {@code platform_user} / {@code platform_user_identity}，
 * 也不碰其他会话。这是 ROLE.03 的验收点——同一个账号开两条会话，一条切身份，
 * 另一条访问 {@code /me} 仍返回原身份。</p>
 *
 * <p>允许自助切换的只有 {@code EMPLOYER} / {@code WORKER}：{@code ADMIN} 只能由后台开通，
 * 把 ADMIN 传进来按参数错误 400 处理，而不是查库后返回 403——
 * 「你没这个身份」与「这个值不许传」是两件事。</p>
 *
 * @since V1
 */
@Service
@RequiredArgsConstructor
public class IdentitySwitchService {

    private static final String INVALID_IDENTITY_MESSAGE = "identityType 只允许 EMPLOYER 或 WORKER";

    private final PlatformUserIdentityMapper platformUserIdentityMapper;
    private final SessionAuthenticator sessionAuthenticator;
    private final SecurityLog securityLog;

    /**
     * 切到目标身份并刷新本会话的 authorities。
     *
     * @param current      当前会话主体
     * @param identityType 目标身份字符串（{@code EMPLOYER} / {@code WORKER}）
     * @return 换过 {@code currentIdentity} 的新主体（已写回 {@code SecurityContextHolder}）
     * @throws ApiException 400 参数不合法 / 403 身份未开通或已禁用
     */
    public LoginUser switchIdentity(LoginUser current, String identityType) {
        IdentityType target = parseTarget(identityType);
        PlatformUserIdentity identity = platformUserIdentityMapper.selectOne(
                new LambdaQueryWrapper<PlatformUserIdentity>()
                        .eq(PlatformUserIdentity::getUserId, current.userId())
                        .eq(PlatformUserIdentity::getIdentityType, target));

        if (identity == null || identity.getStatus() != IdentityStatus.ACTIVE) {
            securityLog.log(SecurityLog.ACTION_SWITCH_IDENTITY, "identity:" + target,
                    current.account(), SecurityLog.RESULT_DENIED);
            throw new ApiException(ErrorCode.IDENTITY_NOT_AVAILABLE,
                    ErrorCode.IDENTITY_NOT_AVAILABLE.getDefaultMessage());
        }

        LoginUser switched = new LoginUser(current.userId(), current.account(),
                current.defaultRole(), target);
        sessionAuthenticator.authenticate(switched);
        securityLog.log(SecurityLog.ACTION_SWITCH_IDENTITY, "identity:" + target,
                current.account(), SecurityLog.RESULT_SUCCESS);
        return switched;
    }

    /** 白名单式解析：不接 {@code valueOf} 的异常语义，ADMIN 与未知值都归为参数错误。 */
    private static IdentityType parseTarget(String identityType) {
        String value = identityType == null ? "" : identityType.trim();
        if (IdentityType.EMPLOYER.name().equals(value)) {
            return IdentityType.EMPLOYER;
        }
        if (IdentityType.WORKER.name().equals(value)) {
            return IdentityType.WORKER;
        }
        throw new ApiException(ErrorCode.INVALID_PARAMETER, INVALID_IDENTITY_MESSAGE);
    }
}
