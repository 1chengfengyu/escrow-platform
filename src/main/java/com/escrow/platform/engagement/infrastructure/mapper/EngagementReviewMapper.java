package com.escrow.platform.engagement.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.engagement.domain.EngagementReview;

/**
 * 签约评价 Mapper（V7）。
 *
 * <p>只有基础 CRUD 与分页，没有自定义 SQL；扫描由
 * {@code common/config/MyBatisMapperScanConfig} 统一负责。</p>
 *
 * @since V7
 */
public interface EngagementReviewMapper extends BaseMapper<EngagementReview> {
}
