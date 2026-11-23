package com.escrow.platform.task.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.task.domain.TaskSlot;

/**
 * {@code task_slot} 的持久化入口。
 *
 * <p>本卡（v2-c2）草稿阶段零行；名额生成与状态迁移归 v2-c5 / v2-c3。
 * 只继承 {@link BaseMapper}，聚合计数（剩余名额 / R0~R12 输入）由调用方按
 * {@code idx_task_slot_task_status} 用条件构造器完成，不写自定义方法与 XML。</p>
 *
 * <p>接口上不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 的
 * {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】）。</p>
 *
 * @since V2
 */
public interface TaskSlotMapper extends BaseMapper<TaskSlot> {
}
