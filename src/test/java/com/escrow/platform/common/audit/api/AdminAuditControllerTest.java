package com.escrow.platform.common.audit.api;

import com.escrow.platform.common.audit.application.AuditQueryService;
import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计接口契约测试（V7）：列表与 CSV 导出。
 *
 * @since V7
 */
@WebMvcTest(controllers = AdminAuditController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class AdminAuditControllerTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditQueryService auditQueryService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(LoginUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    /** 列表按业务单号过滤。 */
    @Test
    void listShouldReturnPage() throws Exception {
        loginAs(ADMIN);
        AuditLogView view = new AuditLogView(1L, "PAYOUT", "700", "SUCCESS", "PAYOUT_EXECUTING",
                "PAYOUT_SUCCESS", 9001L, "ADMIN", "actual=970", LocalDateTime.now());
        when(auditQueryService.list(any(), eq("PAYOUT"), eq("700"), any(), any(), any(), eq(1), eq(20)))
                .thenReturn(PageView.of(List.of(view), 1, 20, 1));

        mockMvc.perform(get("/api/admin/audit").param("bizType", "PAYOUT").param("bizNo", "700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].action").value("SUCCESS"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    /** 导出返回 CSV 附件。 */
    @Test
    void exportShouldReturnCsvAttachment() throws Exception {
        loginAs(ADMIN);
        when(auditQueryService.exportCsv(any(), any(), any(), any(), any(), any()))
                .thenReturn("\uFEFFid,bizType\n1,PAYOUT\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/admin/audit/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("audit.csv")));
    }
}
