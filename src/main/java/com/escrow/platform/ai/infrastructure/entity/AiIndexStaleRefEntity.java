package com.escrow.platform.ai.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 旧版本引用失效登记（V10-c3，表 {@code ai_index_stale_ref}）。
 *
 * <p>唯一键 {@code (version_no, citation_id)}：重复登记直接撞键，无需先查后插。</p>
 *
 * @since V10
 */
@Getter
@Setter
@TableName("ai_index_stale_ref")
public class AiIndexStaleRefEntity {

    @TableId
    private Long id;

    private Integer versionNo;

    private String citationId;

    private String reason;

    private LocalDateTime createdAt;
}
