package com.escrow.platform.common.audit.application;

import com.escrow.platform.common.audit.domain.AuditLog;
import com.escrow.platform.common.audit.infrastructure.mapper.AuditLogMapper;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计流水记录器（V7）。
 *
 * <p>审计是业务事务的一部分：随业务事务提交，业务回滚时审计也回滚，
 * 保证「有审计必有业务」。操作人从会话取；系统任务没有会话时为空。</p>
 *
 * @since V7
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRecorder {

    private static final int MAX_BIZ_NO = 64;
    private static final int MAX_ACTION = 64;
    private static final int MAX_DETAIL = 500;

    private final AuditLogMapper auditLogMapper;

    /**
     * 记录一条审计流水。
     *
     * @param bizType    业务类型
     * @param bizNo      业务单号或资源 ID
     * @param action     动作
     * @param fromStatus 变更前状态，可空
     * @param toStatus   变更后状态，可空
     * @param detail     补充说明，可空
     */
    public void record(String bizType, String bizNo, String action, String fromStatus,
                       String toStatus, String detail) {
        LoginUser operator = currentUser();
        AuditLog log = new AuditLog();
        log.setBizType(truncate(bizType, 32));
        log.setBizNo(truncate(bizNo, MAX_BIZ_NO));
        log.setAction(truncate(action, MAX_ACTION));
        log.setFromStatus(truncate(fromStatus, 32));
        log.setToStatus(truncate(toStatus, 32));
        log.setOperatorId(operator == null ? null : operator.userId());
        log.setOperatorRole(operator == null ? null : operator.currentIdentity().name());
        log.setDetail(truncate(detail, MAX_DETAIL));
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    /**
     * 记录一条审计流水（带显式操作人，用于异步或系统任务）。
     *
     * @param operatorId 操作人账号 ID，可空
     * @param bizType    业务类型
     * @param bizNo      业务单号
     * @param action     动作
     * @param detail     补充说明
     */
    public void recordAs(Long operatorId, String bizType, String bizNo, String action, String detail) {
        AuditLog log = new AuditLog();
        log.setBizType(truncate(bizType, 32));
        log.setBizNo(truncate(bizNo, MAX_BIZ_NO));
        log.setAction(truncate(action, MAX_ACTION));
        log.setOperatorId(operatorId);
        log.setOperatorRole(null);
        log.setDetail(truncate(detail, MAX_DETAIL));
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private static LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
