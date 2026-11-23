package com.escrow.platform.engagement.application;

import com.escrow.platform.engagement.domain.EngagementPerformanceRecord;
import com.escrow.platform.engagement.domain.PerformanceEventType;
import com.escrow.platform.engagement.infrastructure.mapper.EngagementPerformanceRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 履约记录的唯一写入入口（SRS 4.2.2「履约记录」、FR-SIGN.START.02）。
 *
 * <p>记录只追加。{@code (contractId, eventType)} 上有唯一索引，同一签约的同类事件重复写入
 * （重试、并发、历史重放）由唯一键兜底：撞键即视为已记录，记一条日志后返回，不抛给调用方。</p>
 *
 * <p>调用方必须已经在同一事务内完成签约状态的条件更新；本服务不判断业务状态。</p>
 *
 * @since V3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceRecordService {

    private final EngagementPerformanceRecordMapper performanceRecordMapper;

    /**
     * 追加一条履约记录。
     *
     * @param workerId   劳动者用户 ID
     * @param contractId 签约 ID
     * @param eventType  事件类型
     * @param occurredAt 事件发生时间
     */
    @Transactional
    public void record(Long workerId, Long contractId, PerformanceEventType eventType,
                       LocalDateTime occurredAt) {
        EngagementPerformanceRecord record = new EngagementPerformanceRecord();
        record.setWorkerId(workerId);
        record.setContractId(contractId);
        record.setEventType(eventType);
        record.setOccurredAt(occurredAt);
        try {
            performanceRecordMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            // uk_engagement_performance_record_event 兜底：同类事件只记一次
            log.info("履约记录已存在 contractId={} eventType={}", contractId, eventType);
        }
    }
}
