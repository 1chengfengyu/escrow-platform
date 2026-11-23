package com.escrow.platform.common.audit.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.common.audit.domain.AuditLog;

/**
 * 审计流水 Mapper（V7）。只提供插入与查询。
 *
 * @since V7
 */
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
