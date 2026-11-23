package com.escrow.platform.identity.application;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.redis.RateLimiter;
import com.escrow.platform.identity.api.RegisterRequest;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册服务单测（FR-ACCT.REG.01~REG.03、FR-ACCT.ROLE.01）。
 *
 * <p>Mockito 纯单测：不连库、不起 Spring，覆盖「正常双写 / 查重命中 / 字段不合法 /
 * 唯一键竞态」四条路径。</p>
 *
 * @since V1
 */
@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

    @Mock
    private PlatformUserMapper platformUserMapper;

    @Mock
    private PlatformUserIdentityMapper platformUserIdentityMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    /** 限流器在 c4 接在注册入口：void 方法默认不做事，本类只验证注册语义不受影响。 */
    @Mock
    private RateLimiter rateLimiter;

    @InjectMocks
    private RegisterService registerService;

    private static final String CLIENT_IP = "127.0.0.1";

    private static RegisterRequest validRequest() {
        return new RegisterRequest("13800138000", null, "abc12345", " 小明 ", "WORKER");
    }

    /**
     * 正常注册：口令被哈希、账号与身份各插一行、返回 MP 回填的雪花 ID。
     *
     * <p>{@code insert} 被 mock 后不会真的生成 ID，所以用 answer 模拟 MP 的回填行为。</p>
     */
    @Test
    void shouldRegisterUserAndDefaultIdentity() {
        when(platformUserMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("abc12345")).thenReturn("$2a$10$hashed");
        when(platformUserMapper.insert(any(PlatformUser.class))).thenAnswer(invocation -> {
            PlatformUser user = invocation.getArgument(0);
            user.setId(1001L);
            return 1;
        });

        Long userId = registerService.register(validRequest(), CLIENT_IP);

        assertThat(userId).isEqualTo(1001L);
        verify(passwordEncoder).encode("abc12345");

        ArgumentCaptor<PlatformUser> userCaptor = ArgumentCaptor.forClass(PlatformUser.class);
        verify(platformUserMapper).insert(userCaptor.capture());
        PlatformUser user = userCaptor.getValue();
        assertThat(user.getId()).isEqualTo(1001L);
        assertThat(user.getPhone()).isEqualTo("13800138000");
        assertThat(user.getEmail()).isNull();
        assertThat(user.getNickname()).isEqualTo("小明");
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$hashed");
        assertThat(user.getRole()).isEqualTo(UserRole.WORKER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);

        ArgumentCaptor<PlatformUserIdentity> identityCaptor =
                ArgumentCaptor.forClass(PlatformUserIdentity.class);
        verify(platformUserIdentityMapper).insert(identityCaptor.capture());
        PlatformUserIdentity identity = identityCaptor.getValue();
        assertThat(identity.getUserId()).isEqualTo(1001L);
        assertThat(identity.getIdentityType()).isEqualTo(IdentityType.WORKER);
        assertThat(identity.getStatus()).isEqualTo(IdentityStatus.ACTIVE);
    }

    /** 手机号已存在：409 语义的业务异常，且不得写任何一行（FR-ACCT.REG.02）。 */
    @Test
    void shouldRejectWhenPhoneAlreadyExists() {
        when(platformUserMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> registerService.register(validRequest(), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_EXISTS);

        verify(platformUserMapper, never()).insert(any(PlatformUser.class));
        verify(platformUserIdentityMapper, never()).insert(any(PlatformUserIdentity.class));
    }

    /** 字段不合法：INVALID_PARAMETER，且 data.fields 必须指明出错字段（FR-ACCT.REG.01）。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectInvalidParameterWithFields() {
        RegisterRequest invalid =
                new RegisterRequest("12345", null, "abcdefgh", "  ", "ADMIN");

        assertThatThrownBy(() -> registerService.register(invalid, CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    assertThat(apiException.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
                    Map<String, Object> data = (Map<String, Object>) apiException.getData();
                    assertThat(data).containsKey("fields");
                    assertThat((Map<String, String>) data.get("fields"))
                            .containsKeys("phone", "password", "nickname", "role");
                });

        verify(passwordEncoder, never()).encode(anyString());
        verify(platformUserMapper, never()).insert(any(PlatformUser.class));
    }

    /** 查重与插入之间的竞态：唯一键抛 DuplicateKeyException，同样转 ACCOUNT_EXISTS。 */
    @Test
    void shouldTranslateDuplicateKeyIntoAccountExists() {
        when(platformUserMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
        when(platformUserMapper.insert(any(PlatformUser.class)))
                .thenThrow(new DuplicateKeyException("uk_phone"));

        assertThatThrownBy(() -> registerService.register(validRequest(), CLIENT_IP))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_EXISTS);

        verify(platformUserIdentityMapper, never()).insert(any(PlatformUserIdentity.class));
    }
}
