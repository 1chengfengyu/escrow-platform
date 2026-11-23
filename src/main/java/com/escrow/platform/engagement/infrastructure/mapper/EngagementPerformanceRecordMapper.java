package com.escrow.platform.engagement.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.engagement.domain.EngagementPerformanceRecord;

/**
 * 履约记录 Mapper。
 *
 * <p>不加 {@code @Mapper}：扫描由 {@code common/config/MyBatisMapperScanConfig} 统一负责
 * （docs/decisions.md #42，避免 {@code @WebMvcTest} 切片解析不到）。本卡只有只追加的 {@code insert}
 * 与按签约查询，没有自定义 SQL。</p>
 *
 * @since V3
 */
public interface EngagementPerformanceRecordMapper extends BaseMapper<EngagementPerformanceRecord> {
}
