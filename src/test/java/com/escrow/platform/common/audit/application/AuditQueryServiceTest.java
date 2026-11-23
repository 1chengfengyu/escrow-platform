package com.escrow.platform.common.audit.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.common.audit.domain.AuditLog;
import com.escrow.platform.common.audit.infrastructure.mapper.AuditLogMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 审计查询与导出测试（V7）：CSV BOM、表头、转义、管理端权限。
 *
 * @since V7
 */
@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    private static final LoginUser ADMIN =
            new LoginUser(9001L, "admin@example.com", UserRole.ADMIN, IdentityType.ADMIN);
    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private AuditLogMapper auditLogMapper;

    @InjectMocks
    private AuditQueryService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, AuditLog.class);
    }

    /** 导出 CSV：UTF-8 BOM + 表头 + 逗号 / 引号转义。 */
    @Test
    void exportShouldEscapeCsv() {
        AuditLog row = new AuditLog();
        row.setId(1L);
        row.setBizType("PAYOUT");
        row.setBizNo("700");
        row.setAction("SUCCESS");
        row.setDetail("金额 970, 备注 \"已到账\"");
        row.setOperatorId(9001L);
        row.setCreatedAt(LocalDateTime.of(2026, 11, 18, 10, 30));
        when(auditLogMapper.selectList(any())).thenReturn(List.of(row));

        byte[] csv = service.exportCsv(ADMIN, null, null, null, null, null);

        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFFid,bizType,bizNo,action");
        assertThat(text).contains("PAYOUT,700,SUCCESS");
        assertThat(text).contains("\"金额 970, 备注 \"\"已到账\"\"\"");
        assertThat(text).contains("2026-11-18 10:30:00");
    }

    /** 非管理员不能导出。 */
    @Test
    void exportShouldRejectNonAdmin() {
        assertThatThrownBy(() -> service.exportCsv(WORKER, null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(com.escrow.platform.common.error.ErrorCode.FORBIDDEN);
    }
}
