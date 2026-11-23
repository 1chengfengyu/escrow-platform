package com.escrow.platform.engagement.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.engagement.domain.EngagementDeliverable;

/**
 * 交付物 Mapper。
 *
 * <p>不加 {@code @Mapper}：扫描由 {@code common/config/MyBatisMapperScanConfig} 统一负责
 * （docs/decisions.md #42）。本卡只有插入与按签约查询，没有自定义 SQL。</p>
 *
 * @since V3
 */
public interface EngagementDeliverableMapper extends BaseMapper<EngagementDeliverable> {
}
