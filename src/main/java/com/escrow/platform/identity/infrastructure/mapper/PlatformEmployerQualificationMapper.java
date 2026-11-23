package com.escrow.platform.identity.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.identity.domain.PlatformEmployerQualification;

/**
 * {@code platform_employer_qualification} 的持久化入口。
 *
 * <p>V1 的查询与写入都能用条件构造器表达（按 {@code user_id} 查单行、按状态列出待审），
 * 不写自定义方法与 XML。</p>
 *
 * <p>不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 的 {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】）。</p>
 *
 * @since V1
 */
public interface PlatformEmployerQualificationMapper extends BaseMapper<PlatformEmployerQualification> {
}
