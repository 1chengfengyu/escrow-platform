package com.escrow.platform.common.audit.api;

import com.escrow.platform.common.audit.application.AuditQueryService;
import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 审计流水查询与导出（V7，FR-ADMIN.AUDIT.01）。
 *
 * @since V7
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditQueryService auditQueryService;

    /**
     * 分页查询审计流水。
     *
     * @param current    当前登录用户
     * @param bizType    业务类型
     * @param bizNo      业务单号
     * @param operatorId 操作人
     * @param from       起始时间
     * @param to         结束时间
     * @param page       页码
     * @param size       每页条数
     * @return 分页结果
     */
    @GetMapping
    public ApiResponse<PageView<AuditLogView>> list(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditQueryService.list(current, bizType, bizNo, operatorId,
                from, to, page, size));
    }

    /**
     * 导出审计流水 CSV。
     *
     * @param current    当前登录用户
     * @param bizType    业务类型
     * @param bizNo      业务单号
     * @param operatorId 操作人
     * @param from       起始时间
     * @param to         结束时间
     * @return CSV 文件
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        byte[] csv = auditQueryService.exportCsv(current, bizType, bizNo, operatorId, from, to);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename("audit.csv").build());
        return ResponseEntity.ok().headers(headers).body(csv);
    }
}
