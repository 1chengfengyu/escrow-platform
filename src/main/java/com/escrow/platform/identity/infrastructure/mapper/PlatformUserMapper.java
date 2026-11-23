package com.escrow.platform.identity.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.identity.domain.PlatformUser;

/**
 * {@code platform_user} 的持久化入口。
 *
 * <p>只继承 {@link BaseMapper}：V1 的注册与登录用 MP 提供的条件构造器即可完成，
 * 不需要手写 SQL，因此不建 XML（docs/decisions.md #42：没有真实 SQL 时不提前建
 * {@code resources/mapper/}）。</p>
 *
 * <p>接口上不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 的
 * {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】），
 * 两处都写会让「Mapper 到底在哪一层」出现两个答案。</p>
 *
 * @since V1
 */
public interface PlatformUserMapper extends BaseMapper<PlatformUser> {
}
