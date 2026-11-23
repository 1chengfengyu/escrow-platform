package com.escrow.platform.task.api;

import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.TaskDisplayStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务详情（FR-TASK.DETAIL.01 / .02，docs/decisions.md #48）。
 *
 * <p><b>类上的 {@code @JsonInclude(NON_NULL)} 是 FR-TASK.DETAIL.01 的可测试形式</b>：
 * 非当事人视图里 {@code slotOccupancy} 与 {@code escrowAmount} 两个键<b>完全不出现</b>，
 * 而不是出现为 {@code null}。</p>
 *
 * <p>{@code escrowAmount} 取托管总额快照（v2-c6 已接入 fund 域）：已发布任务为托管单
 * {@code total_amount}，草稿为 {@code null}；撤单 / 到期退款后仍显示原托管额。
 * 草稿的当事人视图 {@code slotOccupancy} 是空数组不是 null。</p>
 *
 * @param id                    任务 ID
 * @param title                 标题
 * @param description           描述
 * @param category              类别
 * @param unitPrice             单价（分）
 * @param slotCount             名额数
 * @param startTime             开始时间
 * @param endTime               结束时间
 * @param workLocation          工作地点
 * @param settlementNote        结算说明
 * @param publishedAt           发布时间；草稿为 null
 * @param cancelledAt           撤单时间
 * @param remainingSlots        剩余名额（待占用名额数）
 * @param slotOccupancy         名额占用明细；仅当事人视图有值
 * @param escrowAmount          托管总额（分）；未托管为 null（v2-c6）
 * @param partyView             是否当事人视图
 * @param hasOpenDispute        是否存在未裁决争议（V2 恒 false）
 * @param hasSettlementAbnormal 是否存在待裁决名额
 * @param status                派生展示状态（SRS A.3.2 R0~R12，不落库）
 * @param recruitingClosed      有效招募收口标志（撤单 OR 到期）
 * @since V2
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskDetailView(
        Long id, String title, String description, String category, Long unitPrice,
        Integer slotCount, LocalDateTime startTime, LocalDateTime endTime,
        String workLocation, String settlementNote, LocalDateTime publishedAt,
        LocalDateTime cancelledAt, Integer remainingSlots,
        List<SlotItem> slotOccupancy, Long escrowAmount,
        boolean partyView, boolean hasOpenDispute, boolean hasSettlementAbnormal,
        TaskDisplayStatus status, boolean recruitingClosed) {

    /** 名额占用明细：序号 + 状态。 */
    public record SlotItem(Integer seqNo, SlotStatus status) {
    }
}
