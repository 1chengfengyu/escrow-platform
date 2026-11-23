package com.escrow.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.escrow.platform.identity.domain.IdentityStatus;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.PlatformUser;
import com.escrow.platform.identity.domain.PlatformUserIdentity;
import com.escrow.platform.identity.domain.UserRole;
import com.escrow.platform.identity.domain.UserStatus;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserIdentityMapper;
import com.escrow.platform.identity.infrastructure.mapper.PlatformUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开发环境超管初始化（docs/decisions.md #22 的 V1 落地）。
 *
 * <p>为什么是 {@code DevAdminInitializer} 而不是 {@code DevDataInitializer}：
 * 本类只负责超管，不造任何演示业务数据——名字必须让人一眼知道它不碰任务 / 资金表。</p>
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li>只在 {@code dev} profile 注册，生产不会误建超管；</li>
 *   <li>口令从环境变量 {@code ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD} 读，代码与配置文件里不出现任何口令字面量；</li>
 *   <li>任一缺失只 WARN 一行后跳过，<b>不抛异常</b>——不能让演示环境因为没配环境变量而起不来。</li>
 * </ol>
 *
 * <p>同时插一行 {@code platform_user_identity(ADMIN, ACTIVE)}：否则管理员登录后
 * {@code LoginService} 找不到可用身份，登录直接被 {@code IDENTITY_NOT_AVAILABLE} 拒绝。</p>
 *
 * @since V1
 */
@Slf4j
@Component
@Profile("dev")
public class DevAdminInitializer implements ApplicationRunner {

    private static final String ADMIN_NICKNAME = "平台管理员";

    private final PlatformUserMapper platformUserMapper;
    private final PlatformUserIdentityMapper platformUserIdentityMapper;
    private final PasswordEncoder passwordEncoder;
    private final String initPhone;
    private final String initPassword;

    public DevAdminInitializer(PlatformUserMapper platformUserMapper,
                               PlatformUserIdentityMapper platformUserIdentityMapper,
                               PasswordEncoder passwordEncoder,
                               @Value("${ADMIN_INIT_PHONE:}") String initPhone,
                               @Value("${ADMIN_INIT_PASSWORD:}") String initPassword) {
        this.platformUserMapper = platformUserMapper;
        this.platformUserIdentityMapper = platformUserIdentityMapper;
        this.passwordEncoder = passwordEncoder;
        this.initPhone = initPhone;
        this.initPassword = initPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (initPhone == null || initPhone.isBlank() || initPassword == null || initPassword.isBlank()) {
            log.warn("未配置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD 环境变量，跳过超管初始化");
            return;
        }

        Long adminCount = platformUserMapper.selectCount(
                new LambdaQueryWrapper<PlatformUser>().eq(PlatformUser::getRole, UserRole.ADMIN));
        if (adminCount != null && adminCount > 0) {
            // 幂等：重启不会重复建号；也避免每次启动都做一次无意义的 BCrypt 编码
            log.info("已存在管理员账号，跳过超管初始化");
            return;
        }

        PlatformUser admin = new PlatformUser();
        admin.setPhone(initPhone.trim());
        admin.setNickname(ADMIN_NICKNAME);
        admin.setPasswordHash(passwordEncoder.encode(initPassword));
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        platformUserMapper.insert(admin);

        PlatformUserIdentity identity = new PlatformUserIdentity();
        identity.setUserId(admin.getId());
        identity.setIdentityType(IdentityType.ADMIN);
        identity.setStatus(IdentityStatus.ACTIVE);
        platformUserIdentityMapper.insert(identity);

        // NFR-SEC.04：只打 userId 与链路，手机号 / 口令 / 哈希一律不进日志
        log.info("超管初始化完成 userId={}", admin.getId());
    }
}
