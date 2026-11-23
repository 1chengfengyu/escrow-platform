package com.escrow.platform.identity.api;

import java.util.List;

/**
 * 当前登录者视图（v1-c4 §2.5）。
 *
 * <p>用途之一就是演示 FR-ACCT.ROLE.03：同一账号开两条会话，一条切身份，
 * 另一条的 {@code currentIdentity} 不变——说明「当前身份」是会话级状态，不是账号级字段。</p>
 *
 * @param userId          账号 ID
 * @param nickname        昵称
 * @param defaultRole     注册时选择的默认身份
 * @param currentIdentity 本会话当前身份
 * @param identities      该账号全部已开通（ACTIVE）的身份
 * @since V1
 */
public record IdentityMeResponse(
        Long userId,
        String nickname,
        String defaultRole,
        String currentIdentity,
        List<String> identities) {
}
