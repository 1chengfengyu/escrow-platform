package com.escrow.platform.engagement.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.engagement.domain.EngagementApplication;

/**
 * {@code engagement_application} 的持久化入口。
 *
 * <p>只继承 {@link BaseMapper}：本卡只有插入与「按任务分页」两种访问，
 * 后者用条件构造器完成，避免为一条简单 SQL 另建 XML。</p>
 *
 * <p>接口上不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 的
 * {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】）。</p>
 *
 * @since V3
 */
public interface EngagementApplicationMapper extends BaseMapper<EngagementApplication> {
}
