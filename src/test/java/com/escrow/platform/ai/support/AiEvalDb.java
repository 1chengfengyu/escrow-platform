package com.escrow.platform.ai.support;

import com.escrow.platform.ai.infrastructure.eval.EvalResultRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 离线评测写库的"有就用、没有就算了"入口（V10-c7）。
 *
 * <p>评测是纯 JUnit（不起 Spring 容器），所以这里用环境变量手搓一个最简数据源，
 * 与 {@code application-test.yml} 的取值口径保持一致（{@code DB_HOST} / {@code DB_PORT} /
 * {@code TEST_DB_NAME}）。</p>
 *
 * <p>拿不到 {@code DB_HOST} 就返回 null：默认全量测试不依赖数据库，
 * Judge 分数留在日志里同样可用，只是换版本后没法做趋势对比。</p>
 *
 * @since V10
 */
public final class AiEvalDb {

    private AiEvalDb() {
    }

    /**
     * 按环境变量构建评测写入器。
     *
     * @return 写库可用时返回写入器；未配置 {@code DB_HOST} 时返回 {@code null}
     */
    public static EvalResultRecorder recorderOrNull() {
        String host = System.getenv("DB_HOST");
        if (host == null || host.isBlank()) {
            return null;
        }
        String port = envOr("DB_PORT", "3306");
        String database = envOr("TEST_DB_NAME", "escrow_test");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&sslMode=DISABLED&allowPublicKeyRetrieval=true";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                envOr("DB_USERNAME", "escrow"), envOr("DB_PASSWORD", "dev_escrow"));
        return new EvalResultRecorder(new JdbcTemplate(dataSource));
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
