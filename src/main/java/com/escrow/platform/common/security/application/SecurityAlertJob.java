package com.escrow.platform.common.security.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.common.security.domain.SecurityEvent;
import com.escrow.platform.common.security.infrastructure.mapper.SecurityEventMapper;
import com.escrow.platform.common.web.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 越权告警任务（V7，NFR-SEC.02 四级授权的告警侧）。
 *
 * <p>每小时统计最近一小时的安全事件数，超过阈值打一条 error。告警通道只到日志，
 * 与 V5 账龄告警同一口径；V8 接入监控后再改推送。</p>
 *
 * @since V7
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class SecurityAlertJob {

    private final SecurityEventMapper securityEventMapper;

    @Value("${escrow.security.alert-threshold:10}")
    private int threshold;

    /** 每小时零分执行。 */
    @Scheduled(cron = "${escrow.security.alert-cron:0 0 * * * *}")
    public void scan() {
        TraceContext.runWithNewTrace("security-alert", this::scanOnce);
    }

    private void scanOnce() {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        Long count = securityEventMapper.selectCount(new LambdaQueryWrapper<SecurityEvent>()
                .ge(SecurityEvent::getCreatedAt, since));
        long events = count == null ? 0 : count;
        if (events >= threshold) {
            log.error("越权告警 最近1小时安全事件 count={} threshold={}", events, threshold);
        } else if (events > 0) {
            log.warn("安全事件 最近1小时 count={} threshold={}", events, threshold);
        }
    }
}
