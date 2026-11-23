package com.escrow.platform.identity.api;

import java.time.LocalDateTime;

/**
 * 雇主资质查询视图（{@code GET /api/identity/qualification}）。
 *
 * <p>从未提交时等价于「空记录」：{@code status=UNSUBMITTED}，其余字段为 {@code null}。
 * 该接口<b>不返回 404</b>——未提交是正常状态，不是资源缺失。</p>
 *
 * @param status          审核状态：UNSUBMITTED / PENDING / APPROVED / REJECTED
 * @param subjectType     主体类型，未提交为空
 * @param subjectName     主体名称，未提交为空
 * @param idLast4         证件号后 4 位，未提交为空
 * @param reviewReason    驳回理由 / 审核备注，未审核为空
 * @param reviewedAt      最近一次审核时间，未审核为空
 * @param publishEligible 当前账号是否满足「资质准入」（FR-ACCT.QUAL.03，由 QualificationGate 计算）
 * @since V1
 */
public record QualificationView(
        String status,
        String subjectType,
        String subjectName,
        String idLast4,
        String reviewReason,
        LocalDateTime reviewedAt,
        boolean publishEligible) {
}
