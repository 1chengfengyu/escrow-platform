package com.escrow.platform.settlement.api;

import com.escrow.platform.common.web.ApiResponse;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.settlement.application.ReconHandlingService;
import com.escrow.platform.settlement.application.ReconReport;
import com.escrow.platform.settlement.application.ReconRun;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 对账管理端入口（V6，FR-RECON.HANDLE.01 / FR-RECON.REPORT.01）。
 *
 * <p>Controller 只做参数解析与转发；管理员校验在
 * {@link ReconHandlingService}（与打款处置台同一口径）。</p>
 *
 * @since V6
 */
@RestController
@RequestMapping("/api/admin/recon")
@RequiredArgsConstructor
public class AdminReconController {

    private final ReconHandlingService reconHandlingService;

    /**
     * 差异单列表。
     *
     * @param current 当前登录用户
     * @param status  状态过滤（可空）
     * @param date    对账日过滤（可空）
     * @param page    页码
     * @param size    每页条数
     * @return 分页结果
     */
    @GetMapping("/differences")
    public ApiResponse<PageView<ReconDifferenceView>> list(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reconHandlingService.list(current, status, date, page, size));
    }

    /**
     * 处置一张差异单（补记 / 冲正 / 差额调整 / 忽略）。
     *
     * @param current      当前登录用户
     * @param differenceId 差异单 ID
     * @param request      处置请求
     * @return 处置后的差异单
     */
    @PostMapping("/differences/{differenceId}/handle")
    public ApiResponse<ReconDifferenceView> handle(
            @AuthenticationPrincipal LoginUser current,
            @PathVariable Long differenceId,
            @Valid @RequestBody ReconDecisionRequest request) {
        return ApiResponse.ok(reconHandlingService.handle(current, differenceId, request));
    }

    /**
     * 对账日报。
     *
     * @param current 当前登录用户
     * @param date    对账日期
     * @return 日报
     */
    @GetMapping("/report")
    public ApiResponse<ReconReport> report(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reconHandlingService.report(current, date));
    }

    /**
     * 手动触发对账。
     *
     * @param current 当前登录用户
     * @param date    对账日期
     * @return 批次结果
     */
    @PostMapping("/run")
    public ApiResponse<ReconRun> run(
            @AuthenticationPrincipal LoginUser current,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reconHandlingService.run(current, date));
    }
}
