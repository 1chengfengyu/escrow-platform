package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 账号状态只读入口（V11，FR-ACCT.STATUS.01 / ROLE.01）。
 *
 * <p>其他域（如 {@code common/chat}）不得直查 {@code platform_user} / {@code platform_user_identity}，
 * 只能经本 Service 判断「账号是否可用」「是否已开通劳动者身份」（project_rules §3 域间规则②）。</p>
 *
 * <p>只读、不写审计：它只回答状态，不承担业务判断；是否 403 由调用方决定。</p>
 *
 * @since V11
 */
@Service
@RequiredArgsConstructor
public class AccountStatusQueryService {

    private final PlatformUserMapper platformUserMapper;
    private final PlatformUserIdentityMapper platformUserIdentityMapper;

    /**
     * 账号是否可用（FR-ACCT.STATUS.01）。
     *
     * @param userId 账号 ID，可为 {@code null}
     * @return 账号存在且 {@code status = ACTIVE} 时为 {@code true}
     */
    public boolean isActive(Long userId) {
        if (userId == null) {
            return false;
        }
        PlatformUser user = platformUserMapper.selectById(userId);
        return user != null && user.getStatus() == UserStatus.ACTIVE;
    }

    /**
     * 账号是否可用且已开通劳动者身份（FR-ACCT.ROLE.01）。
     *
     * <p>账号本身不可用时不查身份表：先判账号，少一次查询。</p>
     *
     * @param userId 账号 ID，可为 {@code null}
     * @return 账号 ACTIVE 且存在 {@code (WORKER, ACTIVE)} 身份行时为 {@code true}
     */
    public boolean isActiveWorker(Long userId) {
        if (!isActive(userId)) {
            return false;
        }
        Long count = platformUserIdentityMapper.selectCount(new LambdaQueryWrapper<PlatformUserIdentity>()
                .eq(PlatformUserIdentity::getUserId, userId)
                .eq(PlatformUserIdentity::getIdentityType, IdentityType.WORKER)
                .eq(PlatformUserIdentity::getStatus, IdentityStatus.ACTIVE));
        return count != null && count > 0;
    }
}
