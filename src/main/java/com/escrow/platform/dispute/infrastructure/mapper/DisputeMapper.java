package com.escrow.platform.dispute.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.dispute.domain.Dispute;

/**
 * {@code dispute} 的持久化入口。由 {@code MyBatisMapperScanConfig} 统一扫描。
 *
 * @since V4
 */
public interface DisputeMapper extends BaseMapper<Dispute> {
}
