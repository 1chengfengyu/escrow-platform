package com.escrow.platform.identity.api;

import java.time.LocalDateTime;

/**
 * 管理端账号视图（V7，FR-ADMIN.USER.01）。
 *
 * <p>只回治理需要的字段：不含口令哈希、更新时间。手机号与邮箱原样返回——
 * 能拿到本视图的前提是会话身份为 ADMIN（{@code AdminUserService#list} 已校验），
 * 非管理员在入口就会被 403 挡住。</p>
 *
 * @param userId    账号 ID
 * @param nickname  昵称
 * @param phone     手机号，可空
 * @param email     邮箱，可空
 * @param role      注册时的默认身份
 * @param status    账号状态：ACTIVE / DISABLED / CLOSED
 * @param createdAt 创建时间
 * @since V7
 */
public record AdminUserView(
        Long userId,
        String nickname,
        String phone,
        String email,
        String role,
        String status,
        LocalDateTime createdAt) {
}
