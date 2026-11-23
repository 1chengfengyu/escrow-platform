package com.escrow.platform.ai.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.ai.infrastructure.entity.AiWorkerProfileEntity;

/**
 * {@code ai_worker_profile} 的持久化入口（V10-c3）。
 *
 * <p>不加 {@code @Mapper}：由 {@code common.config.MyBatisMapperScanConfig} 统一扫描
 * {@code com.escrow.platform.**.infrastructure.mapper}，两处都写会出现「Mapper 在哪层」两个答案。</p>
 *
 * @since V10
 */
public interface AiWorkerProfileMapper extends BaseMapper<AiWorkerProfileEntity> {
}
