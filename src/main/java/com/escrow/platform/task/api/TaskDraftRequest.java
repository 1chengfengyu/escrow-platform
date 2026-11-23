package com.escrow.platform.task.api;

import java.time.LocalDateTime;

/**
 * 任务草稿创建 / 编辑请求（FR-TASK.CREATE.01），两个接口共用同一字段集。
 *
 * <p>编辑是<b>全量替换语义</b>，不是 PATCH（卡片 §7.2）：客户端必须提交全部 9 个业务字段。</p>
 *
 * <p>刻意不加 {@code @Valid}：注解校验失败会抛 {@code BindException} 被兜成
 * {@code VALIDATION_FAILED}；SRS 与 docs/decisions.md #30 要求业务字段错误返回
 * {@code INVALID_PARAMETER} 并在 {@code data.fields} 指明字段，因此校验由
 * {@link com.escrow.platform.task.application.TaskValidator} 手工完成。</p>
 *
 * <p>没有 {@code feeRate} / {@code publishedAt} 等派生与发布字段：它们不归客户端管
 * （{@code feeRate} 由 v2-c5 发布时快照，{@code publishedAt} 只由发布动作写）。</p>
 *
 * @param title          标题，trim 后非空，≤60 字
 * @param description    描述，trim 后非空，≤2000 字
 * @param category       类别，trim 后非空，≤32 字（SRS 未给取值集，自由文本）
 * @param unitPrice      单价，单位分，&gt; 0
 * @param slotCount      名额数，1~20
 * @param startTime      开始时间，草稿阶段不得早于当前时间
 * @param endTime        结束时间，必须晚于开始时间
 * @param workLocation   工作地点，trim 后非空，≤128 字
 * @param settlementNote 结算说明，可空，非空时 ≤500 字
 * @since V2
 */
public record TaskDraftRequest(
        String title,
        String description,
        String category,
        Long unitPrice,
        Integer slotCount,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String workLocation,
        String settlementNote) {
}
