package com.escrow.platform.identity.api;

/**
 * 管理员审核雇主资质请求（FR-ADMIN.QUAL.01）。
 *
 * <p>{@code decision} 只接受 {@code APPROVED} / {@code REJECTED}；
 * 驳回时 {@code reason} 必填，通过时可空。</p>
 *
 * @param decision 审核结论，只允许 {@code APPROVED} / {@code REJECTED}
 * @param reason   驳回理由 / 审核备注，驳回时 trim 后 1~255 字符
 * @since V1
 */
public record QualificationReviewRequest(
        String decision,
        String reason) {
}
