package com.escrow.platform.dispute.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.escrow.platform.dispute.domain.DisputeEvidence;
import org.apache.ibatis.annotations.Mapper;

/**
 * 争议证据 Mapper（V6）。
 *
 * @since V6
 */
@Mapper
public interface DisputeEvidenceMapper extends BaseMapper<DisputeEvidence> {
}
