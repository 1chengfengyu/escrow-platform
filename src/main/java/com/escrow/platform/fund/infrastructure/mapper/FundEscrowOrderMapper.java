package com.escrow.platform.fund.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.fund.domain.FundEscrowOrder;

/**
 * {@code fund_escrow_order} 的持久化入口。
 *
 * <p>本卡只有 BaseMapper 默认 CRUD，复杂查询等真正需要时再议。</p>
 *
 * <p>不加 {@code @Mapper}：由 MyBatisMapperScanConfig 的 {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】）。</p>
 *
 * @since V2
 */
public interface FundEscrowOrderMapper extends BaseMapper<FundEscrowOrder> {
}
