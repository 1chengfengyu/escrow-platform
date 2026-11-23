package com.escrow.platform.ai.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.ai.domain.AiRankStrategy;
import com.escrow.platform.ai.domain.TraceStage;
import com.escrow.platform.ai.infrastructure.entity.AiTraceEntity;
import com.escrow.platform.ai.infrastructure.llm.LlmGatewayRouter;
import com.escrow.platform.ai.infrastructure.mapper.AiTraceMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.TraceIdFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI 链路记录与回放（V10-c6）。
 *
 * <p>traceNo 直接取 {@link TraceIdFilter} 写入 MDC 的 traceId：日志里 grep 的链路号
 * 与 {@code GET /api/ai/trace/{traceNo}} 查到的记录必然是同一次请求。非 HTTP 上下文
 * （定时任务、单测）没有 MDC，此时生成同样格式的 32 位号，保证入库约束不破。</p>
 *
 * <p>落库失败不影响主流程：trace 是可观测数据，不能因为它把用户的检索请求打成 500。</p>
 *
 * @since V10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTraceService {

    /** 与 {@code ai_trace.query_text VARCHAR(200)} 对齐。 */
    private static final int MAX_QUERY_TEXT = 200;

    private final AiTraceMapper traceMapper;
    private final ObjectMapper objectMapper;

    /**
     * 记录一次请求的各阶段数据。
     *
     * @param userId      请求方（服务端会话注入）
     * @param queryText   原始查询（已清洗）
     * @param strategy    本次实际策略
     * @param bucket      灰度桶号
     * @param resultCount 返回条数
     * @param stages      阶段数组
     * @return traceNo
     */
    public String record(long userId, String queryText, AiRankStrategy strategy, int bucket,
                         int resultCount, List<TraceStage> stages) {
        String traceNo = currentTraceNo();
        int tokens = stages.stream().mapToInt(TraceStage::tokens).sum();
        AiTraceEntity entity = new AiTraceEntity();
        entity.setTraceNo(traceNo);
        entity.setUserId(userId);
        entity.setQueryText(truncate(queryText));
        entity.setStrategy(strategy.name());
        entity.setBucket(bucket);
        entity.setResultCount(resultCount);
        entity.setStagesJson(writeStages(stages));
        entity.setTotalTokens(tokens);
        entity.setCostMicros(LlmGatewayRouter.estimateMicros(tokens));
        entity.setCreatedAt(LocalDateTime.now());
        try {
            traceMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            // 同一个 traceId 被复用（客户端重放请求头）：不覆盖已有记录，也不让请求失败
            log.warn("trace 重复，跳过落库 traceNo={}", traceNo);
        } catch (RuntimeException ex) {
            log.warn("trace 落库失败 traceNo={}", traceNo, ex);
        }
        return traceNo;
    }

    /**
     * 按 traceNo 回放；不存在转 404，非本人转 403。
     *
     * <p>trace 里有原始查询词与用户ID，属于个人信息，只允许本人查看——
     * 不做归属校验的话，任何登录用户换个 traceNo 就能读到别人的搜索记录。</p>
     *
     * @param traceNo     链路号
     * @param requesterId 请求方（只从会话取）
     */
    public AiResults.Trace replay(String traceNo, long requesterId) {
        AiTraceEntity entity = require(traceNo);
        if (!Long.valueOf(requesterId).equals(entity.getUserId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权查看该链路");
        }
        return new AiResults.Trace(entity.getTraceNo(), entity.getUserId(), entity.getQueryText(),
                entity.getStrategy(), entity.getBucket(), entity.getResultCount(), entity.getTotalTokens(),
                entity.getCostMicros(), readStages(entity.getStagesJson()), entity.getCreatedAt());
    }

    /** 取 trace 实体（反馈服务校验归属与 60 秒窗口时用）。 */
    public AiTraceEntity require(String traceNo) {
        AiTraceEntity entity = traceMapper.selectOne(new LambdaQueryWrapper<AiTraceEntity>()
                .eq(AiTraceEntity::getTraceNo, traceNo));
        if (entity == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "链路记录不存在");
        }
        return entity;
    }

    /** 当前链路号：优先 MDC，缺失时按同一格式生成。 */
    public String currentTraceNo() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String writeStages(List<TraceStage> stages) {
        try {
            return objectMapper.writeValueAsString(stages);
        } catch (Exception ex) {
            // 序列化失败不应阻断业务：退化为空数组，回放时显示"无阶段数据"
            log.warn("stages 序列化失败", ex);
            return "[]";
        }
    }

    private List<TraceStage> readStages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TraceStage>>() {
            });
        } catch (Exception ex) {
            log.warn("stages 反序列化失败，按空数组处理", ex);
            return List.of();
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_QUERY_TEXT ? text : text.substring(0, MAX_QUERY_TEXT);
    }
}
