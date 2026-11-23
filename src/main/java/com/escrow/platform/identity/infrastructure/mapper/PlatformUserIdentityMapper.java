package com.escrow.platform.identity.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.identity.domain.PlatformUserIdentity;

/**
 * {@code platform_user_identity} 的持久化入口。
 *
 * <p>V1 需要的查询都能用条件构造器表达（按 {@code user_id} 查全部身份、
 * 按 {@code (user_id, identity_type)} 查单个身份），所以不写自定义方法与 XML。</p>
 *
 * <p>不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 的 {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】）。</p>
 *
 * @since V1
 */
public interface PlatformUserIdentityMapper extends BaseMapper<PlatformUserIdentity> {
}
