package com.escrow.platform.identity.api;

import com.escrow.platform.common.desensitize.Desensitize;

/**
 * 用户名片（v1-c6 新增载体接口 {@code GET /api/identity/users/{userId}/card}）。
 *
 * <p>为什么 V1 要加这个接口：docs/03 V1 演示第 5 条要求「同一个查询接口，管理员看到完整手机号、
 * 普通用户看到打码」。V1 已有的接口里没有一个「双方都能访问、且返回他人联系方式」的载体，
 * 没有载体就演示不了双层脱敏。V2 任务详情展示雇主名片会直接复用它。</p>
 *
 * <p>只回最小字段集（NFR-SEC.03 最小暴露）：不回口令哈希、账号状态、创建时间。
 * 手机号与邮箱挂 {@link Desensitize}，由 {@code DesensitizeSerializer} 按角色决定是否打码。</p>
 *
 * @param userId   账号 ID
 * @param nickname 昵称
 * @param phone    手机号（非管理员视角打码）
 * @param email    邮箱（非管理员视角打码）
 * @param role     注册时选择的默认身份
 * @since V1
 */
public record UserCardResponse(
        Long userId,
        String nickname,
        @Desensitize(Desensitize.Type.PHONE) String phone,
        @Desensitize(Desensitize.Type.EMAIL) String email,
        String role) {
}
