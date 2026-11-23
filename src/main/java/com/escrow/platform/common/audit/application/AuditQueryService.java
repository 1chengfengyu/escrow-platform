package com.escrow.platform.common.audit.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.common.audit.api.AuditLogView;
import com.escrow.platform.common.audit.domain.AuditLog;
import com.escrow.platform.common.audit.infrastructure.mapper.AuditLogMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计流水查询与导出（V7，FR-ADMIN.AUDIT.01）。
 *
 * @since V7
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_LIMIT = 5_000;
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditLogMapper auditLogMapper;

    /**
     * 分页查询审计流水。
     *
     * @param current    当前登录用户，必须是管理员
     * @param bizType    业务类型过滤，可空
     * @param bizNo      业务单号过滤，可空
     * @param operatorId 操作人过滤，可空
     * @param from       起始时间，可空
     * @param to         结束时间，可空
     * @param page       页码
     * @param size       每页条数
     * @return 当前页
     */
    @Transactional(readOnly = true)
    public PageView<AuditLogView> list(LoginUser current, String bizType, String bizNo,
                                       Long operatorId, LocalDateTime from, LocalDateTime to,
                                       int page, int size) {
        requireAdmin(current);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        Page<AuditLog> result = auditLogMapper.selectPage(new Page<>(safePage, safeSize),
                wrapper(bizType, bizNo, operatorId, from, to));
        List<AuditLogView> items = result.getRecords().stream()
                .map(AuditQueryService::toView)
                .toList();
        return PageView.of(items, safePage, safeSize, result.getTotal());
    }

    /**
     * 导出审计流水 CSV（UTF-8 BOM，最多 5000 行）。
     *
     * @param current    当前登录用户，必须是管理员
     * @param bizType    业务类型过滤，可空
     * @param bizNo      业务单号过滤，可空
     * @param operatorId 操作人过滤，可空
     * @param from       起始时间，可空
     * @param to         结束时间，可空
     * @return CSV 字节（含 BOM）
     */
    @Transactional(readOnly = true)
    public byte[] exportCsv(LoginUser current, String bizType, String bizNo, Long operatorId,
                            LocalDateTime from, LocalDateTime to) {
        requireAdmin(current);
        List<AuditLog> rows = auditLogMapper.selectList(wrapper(bizType, bizNo, operatorId, from, to)
                .orderByAsc(AuditLog::getId)
                .last("LIMIT " + EXPORT_LIMIT));
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("id,bizType,bizNo,action,fromStatus,toStatus,operatorId,operatorRole,detail,createdAt\n");
        for (AuditLog row : rows) {
            csv.append(cell(row.getId())).append(',')
                    .append(cell(row.getBizType())).append(',')
                    .append(cell(row.getBizNo())).append(',')
                    .append(cell(row.getAction())).append(',')
                    .append(cell(row.getFromStatus())).append(',')
                    .append(cell(row.getToStatus())).append(',')
                    .append(cell(row.getOperatorId())).append(',')
                    .append(cell(row.getOperatorRole())).append(',')
                    .append(cell(row.getDetail())).append(',')
                    .append(cell(row.getCreatedAt() == null ? null : CSV_TIME.format(row.getCreatedAt())))
                    .append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static LambdaQueryWrapper<AuditLog> wrapper(String bizType, String bizNo,
                                                        Long operatorId, LocalDateTime from,
                                                        LocalDateTime to) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .orderByDesc(AuditLog::getCreatedAt)
                .orderByDesc(AuditLog::getId);
        if (bizType != null && !bizType.isBlank()) {
            wrapper.eq(AuditLog::getBizType, bizType);
        }
        if (bizNo != null && !bizNo.isBlank()) {
            wrapper.eq(AuditLog::getBizNo, bizNo);
        }
        if (operatorId != null) {
            wrapper.eq(AuditLog::getOperatorId, operatorId);
        }
        if (from != null) {
            wrapper.ge(AuditLog::getCreatedAt, from);
        }
        if (to != null) {
            wrapper.le(AuditLog::getCreatedAt, to);
        }
        return wrapper;
    }

    private static AuditLogView toView(AuditLog log) {
        return new AuditLogView(log.getId(), log.getBizType(), log.getBizNo(), log.getAction(),
                log.getFromStatus(), log.getToStatus(), log.getOperatorId(), log.getOperatorRole(),
                log.getDetail(), log.getCreatedAt());
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    static void requireAdmin(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可访问");
        }
    }
}
