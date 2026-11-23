package com.escrow.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 托管平台启动类。
 *
 * <p>V0 只负责把 Spring 上下文拉起来，业务代码后续按域（identity / task / engagement /
 * fund / settlement / dispute / admin）逐个加入，启动类本身不承载任何业务逻辑。</p>
 *
 * <p>V1 起排除 {@link UserDetailsServiceAutoConfiguration}：否则 Boot 会凭空造一个
 * {@code inMemoryUserDetailsManager} 并打印随机口令，日志里多出一条「Using generated
 * security password」，既暴露噪音也误导排查。认证由业务接口 + 真实 UserDetailsService 承担。</p>
 *
 * <p>V2 起开启定时任务：任务到期扫描（{@code TaskExpiryJob}）是 V2 唯一的 {@code @Scheduled}，
 * 它按 {@code @Profile("!test")} 在测试中不注册。</p>
 */
@EnableScheduling
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class EscrowPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(EscrowPlatformApplication.class, args);
    }
}
