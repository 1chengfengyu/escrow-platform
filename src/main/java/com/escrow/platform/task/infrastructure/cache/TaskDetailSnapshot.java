package com.escrow.platform.task.infrastructure.cache;

import com.escrow.platform.task.domain.SlotStatus;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSlot;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务详情缓存快照（v2-c4，docs/decisions.md #49）。
 *
 * <p><b>只缓存与观察者无关的数据</b>：13 个展示字段 + 两个计数 + 完整名额列表。
 * 谁在看、是否当事人、要不要带名额明细，这些随请求变化，一律不进快照；
 * 每次请求读出快照后再按观察者实时投影，避免不同观察者互相看到对方的结果。</p>
 *
 * <p>{@code slots} 保存 {@code seqNo + status} 全量列表，计数 {@code available} /
 * {@code pendingArbitration} 由构建时一次算好，读路径不再重算。</p>
 *
 * @param id                 任务 ID
 * @param employerId         雇主用户 ID（用于实时判定当事人）
 * @param title              标题
 * @param description        描述
 * @param category           类别
 * @param unitPrice          单价（分）
 * @param slotCount          名额数
 * @param startTime          开始时间
 * @param endTime            结束时间
 * @param workLocation       工作地点
 * @param settlementNote     结算说明
 * @param publishedAt        发布时间；草稿为 null
 * @param cancelledAt        撤单时间
 * @param escrowAmount       托管总额（分）；未托管为 null（v2-c6，来自 fund 域托管单）
 * @param available          待占用名额数
 * @param pendingArbitration 待裁决名额数
 * @param slots              名额列表（序号 + 状态）
 * @since V2
 */
public record TaskDetailSnapshot(
        Long id,
        Long employerId,
        String title,
        String description,
        String category,
        Long unitPrice,
        Integer slotCount,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String workLocation,
        String settlementNote,
        LocalDateTime publishedAt,
        LocalDateTime cancelledAt,
        Long escrowAmount,
        int available,
        int pendingArbitration,
        List<SlotSnapshot> slots) {

    /** 名额快照：序号 + 状态。 */
    public record SlotSnapshot(Integer seqNo, SlotStatus status) {
    }

    /**
     * 由实体构建快照，顺带算好两个计数。
     *
     * @param task         任务实体
     * @param sourceSlots  名额列表（调用方已按 {@code seqNo} 升序）
     * @param escrowAmount 托管总额（分）；未托管为 null（v2-c6，仅缓存 miss 时由 fund 域查询）
     * @return 与观察者无关的快照
     */
    public static TaskDetailSnapshot from(Task task, List<TaskSlot> sourceSlots, Long escrowAmount) {
        int available = 0;
        int pendingArbitration = 0;
        List<SlotSnapshot> slots = new ArrayList<>(sourceSlots.size());
        for (TaskSlot slot : sourceSlots) {
            if (slot.getStatus() == SlotStatus.SLOT_AVAILABLE) {
                available++;
            } else if (slot.getStatus() == SlotStatus.SLOT_PENDING_ARBITRATION) {
                pendingArbitration++;
            }
            slots.add(new SlotSnapshot(slot.getSeqNo(), slot.getStatus()));
        }
        return new TaskDetailSnapshot(
                task.getId(),
                task.getEmployerId(),
                task.getTitle(),
                task.getDescription(),
                task.getCategory(),
                task.getUnitPrice(),
                task.getSlotCount(),
                task.getStartTime(),
                task.getEndTime(),
                task.getWorkLocation(),
                task.getSettlementNote(),
                task.getPublishedAt(),
                task.getCancelledAt(),
                escrowAmount,
                available,
                pendingArbitration,
                List.copyOf(slots));
    }
}
