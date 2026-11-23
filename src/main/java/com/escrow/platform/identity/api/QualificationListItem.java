package com.escrow.platform.identity.api;

import java.time.LocalDateTime;

/**
 * 管理端「雇主资质列表」的一项（{@code GET /api/admin/qualifications}）。
 *
 * <p>带 {@code nickname} 是因为管理端要能直接认人：只给 {@code userId} 还需要再查一次账号，
 * 而昵称就在同一域的 {@code platform_user} 上（SRS 4.2.2 写明「昵称用于管理端查询」）。</p>
 *
 * @param userId      账号 ID
 * @param nickname    昵称
 * @param subjectType 主体类型
 * @param subjectName 主体名称
 * @param idLast4     证件号后 4 位
 * @param submittedAt 最近一次提交时间，列表按它升序（先到先审）
 * @since V1
 */
public record QualificationListItem(
        Long userId,
        String nickname,
        String subjectType,
        String subjectName,
        String idLast4,
        LocalDateTime submittedAt) {
}
