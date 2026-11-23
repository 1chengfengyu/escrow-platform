package com.escrow.platform.ai.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 链路 trace（V10-c6，表 {@code ai_trace}）。
 *
 * <p>{@code traceNo} 直接复用 {@code TraceIdFilter} 写入 MDC 的 traceId，不另生成：
 * 这样按 traceNo 查回放的记录与日志里的 traceId 天然是同一条链路。</p>
 *
 * <p>{@code stagesJson} 是阶段数组，每次模型调用的明细作为阶段内的 {@code calls} 元素——
 * 按 N1 裁决不建 {@code ai_call_log}。</p>
 *
 * @since V10
 */
@Getter
@Setter
@TableName("ai_trace")
public class AiTraceEntity {

    @TableId
    private Long id;

    private String traceNo;

    private Long userId;

    private String queryText;

    /** WEIGHTED / RERANK。 */
    private String strategy;

    /** userId 哈希桶号 0~9999。 */
    private Integer bucket;

    private Integer resultCount;

    private String stagesJson;

    private Integer totalTokens;

    /** 成本，百万分之一元。 */
    private Long costMicros;

    private LocalDateTime createdAt;
}
