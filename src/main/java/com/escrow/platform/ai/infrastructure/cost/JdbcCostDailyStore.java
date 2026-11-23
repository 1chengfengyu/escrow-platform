package com.escrow.platform.ai.infrastructure.cost;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.escrow.platform.ai.domain.AiPhase;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai_cost_daily} 的 JDBC 实现（V10-c1 修复）。
 *
 * <p>累加用 {@code INSERT ... ON DUPLICATE KEY UPDATE}，由唯一键
 * {@code uk_ai_cost_daily(stat_date, phase)} 兜住并发：两个实例同时记账时，
 * 后到的那条走 UPDATE 分支把数值加上去，不需要应用层加锁，也不会丢更新。</p>
 *
 * <p>新值不用 {@code VALUES(col)}（MySQL 8.0.20 起标记废弃，会刷告警），
 * 改为把参数重复传一遍，语义等价且跨版本稳定。</p>
 *
 * @since V10
 */
@RequiredArgsConstructor
public class JdbcCostDailyStore implements CostDailyStore {

    private static final String ACCUMULATE_SQL = """
            INSERT INTO ai_cost_daily (id, stat_date, phase, tokens, cost_micros)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE tokens = tokens + ?, cost_micros = cost_micros + ?
            """;

    private static final String USAGE_SQL = """
            SELECT phase, tokens, cost_micros FROM ai_cost_daily WHERE stat_date = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void accumulate(LocalDate date, AiPhase phase, int tokens, long costMicros) {
        int safeTokens = Math.max(0, tokens);
        long safeMicros = Math.max(0L, costMicros);
        jdbcTemplate.update(ACCUMULATE_SQL, IdWorker.getId(), Date.valueOf(date), phase.name(),
                safeTokens, safeMicros, safeTokens, safeMicros);
    }

    @Override
    public DailyUsage usageOn(LocalDate date) {
        List<Map.Entry<AiPhase, long[]>> rows = jdbcTemplate.query(USAGE_SQL,
                (rs, rowNum) -> Map.entry(
                        AiPhase.valueOf(rs.getString("phase")),
                        new long[]{rs.getInt("tokens"), rs.getLong("cost_micros")}),
                Date.valueOf(date));
        if (rows.isEmpty()) {
            return DailyUsage.empty();
        }
        Map<AiPhase, Integer> tokens = new EnumMap<>(AiPhase.class);
        long micros = 0L;
        for (Map.Entry<AiPhase, long[]> row : rows) {
            tokens.put(row.getKey(), (int) row.getValue()[0]);
            micros += row.getValue()[1];
        }
        return new DailyUsage(tokens, micros);
    }
}
