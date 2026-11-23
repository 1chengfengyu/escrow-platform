package com.escrow.platform.identity.api;

/**
 * 雇主资质提交请求（FR-ACCT.QUAL.01），字段固定 3 个，不再扩张。
 *
 * <p>刻意不加 {@code @Valid}：注解校验失败会抛 {@code BindException} 被兜成
 * {@code VALIDATION_FAILED}；SRS 与 docs/decisions.md #30 要求业务字段错误返回
 * {@code INVALID_PARAMETER} 并在 {@code data.fields} 指明字段，因此校验由
 * {@link com.escrow.platform.identity.application.QualificationValidator} 手工完成。</p>
 *
 * <p>没有「状态」字段：{@code APPROVED} 只能由管理员审核产生，客户端提交时指定状态
 * 等于绕过审核（FR-ACCT.QUAL.02「仅管理员可变更」）。</p>
 *
 * @param subjectType 主体类型，只允许 {@code INDIVIDUAL} / {@code SOLE_PROPRIETOR}
 * @param subjectName 主体名称，trim 后 1~128 字符
 * @param idLast4     证件号后 4 位，必须为 4 位数字
 * @since V1
 */
public record QualificationSubmitRequest(
        String subjectType,
        String subjectName,
        String idLast4) {
}
