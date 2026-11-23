package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.api.IdentityMeResponse;
import com.escrow.platform.identity.api.UserCardResponse;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 当前登录者查询（v1-c4 §2.5，{@code GET /api/identity/me}）。
 *
 * <p>{@code currentIdentity} 取会话里的值而不是查库重算：它表示「这条会话正在用什么身份」，
 * 两条会话可能不同（FR-ACCT.ROLE.03）。查库重算会把另一条会话的切换结果覆盖进来，
 * 「一条切、一条不变」的演示立刻失效。</p>
 *
 * @since V1
 */
@Service
@RequiredArgsConstructor
public class IdentityProfileService {

    private final PlatformUserMapper platformUserMapper;
    private final PlatformUserIdentityMapper platformUserIdentityMapper;

    /**
     * 组装当前登录者视图。
     *
     * @param current 会话主体（由 {@code @AuthenticationPrincipal} 注入）
     * @return 账号信息 + 当前身份 + 全部已开通身份
     */
    public IdentityMeResponse me(LoginUser current) {
        PlatformUser user = platformUserMapper.selectById(current.userId());
        List<String> identities = platformUserIdentityMapper.selectList(
                        new LambdaQueryWrapper<PlatformUserIdentity>()
                                .eq(PlatformUserIdentity::getUserId, current.userId())
                                .eq(PlatformUserIdentity::getStatus, IdentityStatus.ACTIVE))
                .stream()
                .map(identity -> identity.getIdentityType().name())
                .toList();
        String nickname = user == null ? null : user.getNickname();
        return new IdentityMeResponse(current.userId(), nickname, current.defaultRole().name(),
                current.currentIdentity().name(), identities);
    }

    /**
     * 查询他人名片（v1-c6 §3.3，NFR-SEC.03）。
     *
     * <p>返回的是<b>未脱敏原值</b>：打码与否由 {@code DesensitizeSerializer} 在序列化时按角色决定。
     * 服务层提前打码会导致管理员也看不到完整号码，「管理级可见」这条需求就没有落点了。</p>
     *
     * @param userId 目标账号 ID
     * @return 名片视图（手机号 / 邮箱带 {@code @Desensitize} 标记）
     * @throws ApiException 目标账号不存在（404 {@code RESOURCE_NOT_FOUND}）
     */
    public UserCardResponse card(Long userId) {
        PlatformUser user = platformUserMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "目标用户不存在");
        }
        return new UserCardResponse(user.getId(), user.getNickname(), user.getPhone(), user.getEmail(),
                user.getRole().name());
    }

    /**
     * 批量取公开昵称（FR-SIGN.APPLY.07、FR-ACCT.PROFILE.02）。
     *
     * <p>只返回昵称：报名者列表属于「本任务报名者」这一当事人级资源，
     * 但劳动者的姓名、联系方式、证件与简历在未签约时不得展示，因此跨域入口
     * 刻意只给公开字段，避免调用方顺手把整张名片带出去。</p>
     *
     * @param userIds 目标账号 ID 集合
     * @return userId → 昵称；不存在的账号不放入 Map（调用方按空串兜底）
     */
    public Map<Long, String> nicknames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return platformUserMapper.selectBatchIds(userIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(PlatformUser::getId,
                        user -> user.getNickname() == null ? "" : user.getNickname(),
                        (left, right) -> left));
    }
}
