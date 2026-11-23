package com.escrow.platform.engagement.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.engagement.domain.EngagementContract;

/**
 * {@code engagement_contract} 的持久化入口。
 *
 * <p>只继承 {@link BaseMapper}：本卡只有「插入」与「按报名回查」两种访问，
 * 后者用条件构造器完成，不为一条简单 SQL 另建 XML。</p>
 *
 * <p>接口上不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 的
 * {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】）。</p>
 *
 * @since V3
 */
public interface EngagementContractMapper extends BaseMapper<EngagementContract> {
}
