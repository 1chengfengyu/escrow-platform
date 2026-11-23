package com.escrow.platform.settlement.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.settlement.domain.PayoutOrder;

/**
 * {@code payout_order} 的持久化入口。
 *
 * <p>本卡只有 BaseMapper 默认 CRUD，复杂查询等真正需要时再议。</p>
 *
 * <p>不加 {@code @Mapper}：由 MyBatisMapperScanConfig 的 {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】）。</p>
 *
 * @since V2
 */
public interface PayoutOrderMapper extends BaseMapper<PayoutOrder> {
}
