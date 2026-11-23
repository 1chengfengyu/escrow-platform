package com.escrow.platform.ai.application;

import com.escrow.platform.ai.domain.InjectionGuard;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.ai.infrastructure.entity.AiWorkerProfileEntity;
import com.escrow.platform.ai.infrastructure.mapper.AiWorkerProfileMapper;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像写入（V10-c3）：冷启动允许缺报价、更新走显式 set、未填写转 404。
 *
 * @since V10
 */
@ExtendWith(MockitoExtension.class)
class AiProfileServiceTest {

    private static final LoginUser WORKER =
            new LoginUser(3001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Mock
    private AiWorkerProfileMapper profileMapper;

    private AiProfileService service;

    /** LambdaUpdateWrapper 需要实体元数据，纯单测里显式初始化。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiWorkerProfileEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AiProfileService(profileMapper, new InjectionGuard(), new ObjectMapper());
    }

    /** 冷启动：只填技能也能入库，报价标 MISSING 而不是 0。 */
    @Test
    void v10c3ColdStartProfileAllowed() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        AiResults.Profile result = service.upsert(WORKER,
                new AiResults.ProfileCommand(List.of("短视频剪辑", "拍摄"), null, null, null, true));

        ArgumentCaptor<AiWorkerProfileEntity> captor = ArgumentCaptor.forClass(AiWorkerProfileEntity.class);
        verify(profileMapper).insert(captor.capture());
        AiWorkerProfileEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(3001L);
        assertThat(saved.getSkills()).isEqualTo("短视频剪辑,拍摄");
        assertThat(saved.getExpectedUnitPrice()).isNull();
        assertThat(saved.getProfileVersion()).isEqualTo(1);
        assertThat(saved.getSourceFlags()).contains("MISSING");

        assertThat(result.expectedUnitPrice()).isNull();
        assertThat(result.sources()).containsEntry("expectedUnitPrice", "MISSING");
        assertThat(result.sources()).containsEntry("skills", "USER_INPUT");
    }

    /** 更新：显式 set（允许清空），版本号加一。 */
    @Test
    void v10c3UpdateBumpsVersion() {
        AiWorkerProfileEntity existing = new AiWorkerProfileEntity();
        existing.setId(9L);
        existing.setUserId(3001L);
        existing.setSkills("短视频剪辑");
        existing.setExpectedUnitPrice(45000L);
        existing.setCompletedOrders(12);
        existing.setRatingX100(460);
        existing.setProfileVersion(3);
        existing.setSourceFlags("{}");
        when(profileMapper.selectOne(any())).thenReturn(existing);

        AiResults.Profile result = service.upsert(WORKER,
                new AiResults.ProfileCommand(List.of("短视频剪辑"), 3, 50000L, "北京", true));

        verify(profileMapper).update(isNull(), any());
        assertThat(result.profileVersion()).isEqualTo(4);
        assertThat(result.expectedUnitPrice()).isEqualTo(50000L);
        assertThat(result.location()).isEqualTo("北京");
    }

    /** 没填过画像：404，不返回一份编造的空画像。 */
    @Test
    void v10c3MissingProfileIsNotFound() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.me(WORKER))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
