package com.escrow.platform.identity.api;

import java.util.List;

/**
 * 登录响应（v1-c4 §2.2）。
 *
 * <p>不回口令哈希、不回手机号与邮箱（NFR-SEC.04）：登录者本人已经知道自己用什么登录的，
 * 回显只会给会话劫持者多送一份信息。</p>
 *
 * @param userId          账号 ID
 * @param nickname        昵称
 * @param defaultRole     注册时选择的默认身份
 * @param currentIdentity 本次会话生效的身份
 * @param identities      该账号全部已开通（ACTIVE）的身份，供前端渲染切换菜单
 * @since V1
 */
public record LoginResponse(
        Long userId,
        String nickname,
        String defaultRole,
        String currentIdentity,
        List<String> identities) {
}
