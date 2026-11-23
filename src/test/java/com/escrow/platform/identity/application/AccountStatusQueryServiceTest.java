package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 账号状态只读入口单测（V11，FR-ACCT.STATUS.01 / ROLE.01）。
 *
 * <p>只 mock 两张 identity 表：锁「不存在 / null id / 非 ACTIVE 一律 false」与
 * 「身份表只在账号可用时查询」。</p>
 *
 * @since V11
 */
@ExtendWith(MockitoExtension.class)
class AccountStatusQueryServiceTest {

    private static final Long USER_ID = 2001L;

    @Mock
    private PlatformUserMapper platformUserMapper;

    @Mock
    private PlatformUserIdentityMapper platformUserIdentityMapper;

    @InjectMocks
    private AccountStatusQueryService accountStatusQueryService;

    /** {@code LambdaQueryWrapper} 需要 TableInfo 缓存；纯单测没有 MyBatis 启动过程，手工初始化一次。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlatformUser.class);
        TableInfoHelper.initTableInfo(assistant, PlatformUserIdentity.class);
    }

    /** null id 不查库，直接 false。 */
    @Test
    void isActiveShouldReturnFalseForNullId() {
        assertThat(accountStatusQueryService.isActive(null)).isFalse();
        verifyNoInteractions(platformUserMapper);
    }

    /** 账号不存在 → false。 */
    @Test
    void isActiveShouldReturnFalseWhenUserMissing() {
        when(platformUserMapper.selectById(USER_ID)).thenReturn(null);

        assertThat(accountStatusQueryService.isActive(USER_ID)).isFalse();
    }

    /** DISABLED → false（FR-ACCT.STATUS.01）。 */
    @Test
    void isActiveShouldReturnFalseWhenDisabled() {
        when(platformUserMapper.selectById(USER_ID)).thenReturn(user(UserStatus.DISABLED));

        assertThat(accountStatusQueryService.isActive(USER_ID)).isFalse();
    }

    /** CLOSED → false：注销不是删除，但同样不可用。 */
    @Test
    void isActiveShouldReturnFalseWhenClosed() {
        when(platformUserMapper.selectById(USER_ID)).thenReturn(user(UserStatus.CLOSED));

        assertThat(accountStatusQueryService.isActive(USER_ID)).isFalse();
    }

    /** ACTIVE → true。 */
    @Test
    void isActiveShouldReturnTrueWhenActive() {
        when(platformUserMapper.selectById(USER_ID)).thenReturn(user(UserStatus.ACTIVE));

        assertThat(accountStatusQueryService.isActive(USER_ID)).isTrue();
    }

    /** 账号可用且 WORKER 身份 ACTIVE → true。 */
    @Test
    void isActiveWorkerShouldRequireActiveWorkerIdentity() {
        when(platformUserMapper.selectById(USER_ID)).thenReturn(user(UserStatus.ACTIVE));
        when(platformUserIdentityMapper.selectCount(any())).thenReturn(1L);

        assertThat(accountStatusQueryService.isActiveWorker(USER_ID)).isTrue();
    }

    /** 有账号但没开通劳动者身份 → false。 */
    @Test
    void isActiveWorkerShouldReturnFalseWhenNoWorkerIdentity() {
        when(platformUserMapper.selectById(USER_ID)).thenReturn(user(UserStatus.ACTIVE));
        when(platformUserIdentityMapper.selectCount(any())).thenReturn(0L);

        assertThat(accountStatusQueryService.isActiveWorker(USER_ID)).isFalse();
    }

    /** 账号被禁用时不查身份表（少一次查询，也避免泄露身份信息）。 */
    @Test
    void isActiveWorkerShouldSkipIdentityQueryWhenAccountDisabled() {
        when(platformUserMapper.selectById(USER_ID)).thenReturn(user(UserStatus.DISABLED));

        assertThat(accountStatusQueryService.isActiveWorker(USER_ID)).isFalse();
        verifyNoInteractions(platformUserIdentityMapper);
    }

    private static PlatformUser user(UserStatus status) {
        PlatformUser user = new PlatformUser();
        user.setId(USER_ID);
        user.setNickname("劳动者乙");
        user.setStatus(status);
        return user;
    }
}
