package com.escrow.platform.ai.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识索引版本（V10-c3，表 {@code ai_index_version}）。
 *
 * <p>同一时刻最多一个 ACTIVE：切换时旧版本转 RETIRED，其引用由
 * {@link AiIndexStaleRefEntity} 登记失效，避免答案继续引用已下线条目。</p>
 *
 * @since V10
 */
@Getter
@Setter
@TableName("ai_index_version")
public class AiIndexVersionEntity {

    @TableId
    private Long id;

    private Integer versionNo;

    /** BUILDING / ACTIVE / RETIRED。 */
    private String status;

    private Integer chunkCount;

    private LocalDateTime activatedAt;

    private LocalDateTime createdAt;
}
