package com.escrow.platform.task.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务名额，对应表 {@code task_slot}（由 {@code V5__v2_task_tables.sql} 建好）。
 *
 * <p><b>不存冻结金额</b>：SRS 4.2.2 名额行虽列了「冻结金额」，但 v2-c1 已在
 * {@code fund_escrow_detail.amount} 落同一金额并带 {@code uk_fund_escrow_detail_slot(slot_id)}；
 * 金额唯一真相源在 fund 域（docs/decisions.md #47），两处都存就是第二真相源。</p>
 *
 * <p>名额在发布事务内生成（v2-c5），草稿阶段本表零行（docs/decisions.md #47）。
 * {@code status} 无默认值，插入必须显式给值；不存逻辑删除列。</p>
 *
 * @since V2
 */
@Getter
@Setter
@TableName("task_slot")
public class TaskSlot {

    /** 雪花 ID，全局 {@code assign_id}，此处不重复写 type（docs/decisions.md #14）。 */
    @TableId
    private Long id;

    /** 任务 ID，逻辑外键 task.id；(task_id, seq_no) 唯一（uk_task_slot_task_seq）。 */
    private Long taskId;

    /** 名额序号，1~20（ck_task_slot_seq）。 */
    private Integer seqNo;

    /** 名额状态，九值之一（ck_task_slot_status）；状态迁移归 v2-c5 / v2-c3。 */
    private SlotStatus status;

    /** 报名 ID，逻辑外键（可空）；对应后续卡的报名表，避免 c3/c4 再加 ALTER。 */
    private Long applicationId;

    /** 签约 ID，逻辑外键（可空）；对应后续卡的签约表。 */
    private Long engagementId;

    /** 创建时间，数据库默认值写入。 */
    private LocalDateTime createdAt;

    /** 更新时间，数据库 ON UPDATE 维护。 */
    private LocalDateTime updatedAt;
}
