package com.escrow.platform.settlement.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.settlement.domain.PayoutVerificationRecord;

/**
 * {@code payout_verification} 的持久化入口（V5）。
 *
 * <p>不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 统一扫描。</p>
 *
 * @since V5
 */
public interface PayoutVerificationMapper extends BaseMapper<PayoutVerificationRecord> {
}
