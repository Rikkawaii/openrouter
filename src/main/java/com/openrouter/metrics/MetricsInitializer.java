package com.openrouter.metrics;

import com.openrouter.config.RouterProperties;
import com.openrouter.service.DailyStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// todo: 学习这个CommandLineRunner,这个接口的作用是在spring容器启动后执行run方法
@Slf4j
@Component
public class MetricsInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final MetricsRegistry metricsRegistry;
    private final RouterProperties routerProperties;
    private final DailyStatsService dailyStatsService;
    private final com.openrouter.trace.TraceLogger traceLogger;

    public MetricsInitializer(JdbcTemplate jdbcTemplate, MetricsRegistry metricsRegistry,
            RouterProperties routerProperties, DailyStatsService dailyStatsService,
            com.openrouter.trace.TraceLogger traceLogger) {
        this.jdbcTemplate = jdbcTemplate;
        this.metricsRegistry = metricsRegistry;
        this.routerProperties = routerProperties;
        this.dailyStatsService = dailyStatsService;
        this.traceLogger = traceLogger;
    }

    @Override
    public void run(String... args) {
        traceLogger.log("INFO", "🚀 [1/3] 系统启动：正在从 SQLite 恢复通道动态配置...");
        List<RouterProperties.Channel> channels = routerProperties.getChannels();
        if (channels == null || channels.isEmpty()) {
            traceLogger.log("WARN", "⚠️ 未发现任何配置通道，跳过初始化。");
            return;
        }

        // --- 核心段落 A: 恢复通道动态设置 (开关、权重) ---
        for (RouterProperties.Channel channel : channels) {
            String sql = "SELECT enabled, base_weight FROM channel_config WHERE channel_id = ?";
            List<Map<String, Object>> configs = jdbcTemplate.queryForList(sql, channel.getId());
            if (!configs.isEmpty()) {
                Map<String, Object> conf = configs.get(0);
                Integer bw = (Integer) conf.get("base_weight");
                Object en = conf.get("enabled");

                if (bw != null) channel.setBaseWeight(bw);
                if (en != null) {
                    if (en instanceof Number) {
                        channel.setEnabled(((Number) en).intValue() == 1);
                    } else if (en instanceof Boolean) {
                        channel.setEnabled((Boolean) en);
                    }
                }
            }
        }
        traceLogger.log("INFO", "✅ 通道开关、权重等动态配置预加载完成。");

        // --- 核心段落 B: 预热通道健康指标 (Token、成功率、延迟 EMA) ---
        traceLogger.log("INFO", "🚀 [2/3] 系统启动：正在预热各通道历史健康指标 (Metrics Warmup)...");
        
        // 1. 恢复 Token 账单
        String tokenSql = "SELECT channel_id, model, SUM(prompt_tokens) as p, SUM(completion_tokens) as c " +
                          "FROM request_log GROUP BY channel_id, model";
        List<Map<String, Object>> tokenStats = jdbcTemplate.queryForList(tokenSql);
        long totalTokensRecovered = 0;
        for (Map<String, Object> row : tokenStats) {
            long p = parseLongSafely(row.get("p"));
            long c = parseLongSafely(row.get("c"));
            ModelMetrics metrics = metricsRegistry.getMetrics((String) row.get("channel_id"));
            if (metrics != null) {
                metrics.addTokens((String) row.get("model"), p, c);
                totalTokensRecovered += (p + c);
            }
        }

        // 2. 恢复调用计数与模型延迟 EMA
        for (RouterProperties.Channel channel : channels) {
            // 2.1 调用次数与失败计数
            String callSql = "SELECT COUNT(*) as calls, SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) as err " +
                             "FROM model_call_log WHERE channel_id = ?";
            Map<String, Object> calls = jdbcTemplate.queryForMap(callSql, channel.getId());
            ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
            if (metrics != null) {
                metrics.recordCalls(parseLongSafely(calls.get("calls")));
                metrics.recordErrors(parseLongSafely(calls.get("err")));

                // 2.2 EMA 延迟预热 (最近 100 条)
                String durSql = "SELECT AVG(duration_ms) as avg_dur FROM (" +
                                "SELECT duration_ms FROM model_call_log " +
                                "WHERE channel_id = ? AND success = 1 " +
                                "ORDER BY created_at DESC LIMIT 100)";
                List<Map<String, Object>> res = jdbcTemplate.queryForList(durSql, channel.getId());
                if (!res.isEmpty() && res.get(0).get("avg_dur") != null) {
                    metrics.recordModelLatency(((Number) res.get(0).get("avg_dur")).longValue());
                }
            }
        }
        traceLogger.log("INFO", String.format("✅ 各通道健康指标预热完成。累计恢复 Token 消耗: %d", totalTokensRecovered));

        // --- 核心段落 C: 全局大盘初始化 (归档昨日数据 + 初始化内存计数器) ---
        traceLogger.log("INFO", "🚀 [3/3] 系统启动：正在同步全局历史概览与大盘计数器...");
        dailyStatsService.checkAndSyncMissing();
        
        try {
            // 混合模式初始化统计 (daily_stats 历史 + 今日增量)
            Map<String, Object> history = jdbcTemplate.queryForMap("""
                SELECT SUM(total_requests) as total, SUM(total_requests - failed_requests) as succ,
                       SUM(avg_duration * (total_requests - failed_requests)) as dur_sum FROM daily_stats""");
            
            Map<String, Object> today = jdbcTemplate.queryForMap("""
                SELECT AVG(total_duration_ms) as avg, COUNT(*) as total, 
                       SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as succ 
                FROM request_log WHERE date(created_at) = date('now', 'localtime')""");

            long finalSuccess = parseLongSafely(history.get("succ")) + parseLongSafely(today.get("succ"));
            long finalTotal = parseLongSafely(history.get("total")) + parseLongSafely(today.get("total"));
            long finalAvgDur = finalSuccess == 0 ? 0 : (long) (
                (parseDoubleSafely(history.get("dur_sum")) + (parseDoubleSafely(today.get("avg")) * parseLongSafely(today.get("succ")))) / finalSuccess
            );

            metricsRegistry.initGlobalStats(finalAvgDur, finalSuccess, finalTotal);
            traceLogger.log("INFO", String.format("✅ 全局历史概览同步完成。总请求: %d, 平均响应: %d ms", finalTotal, finalAvgDur));
        } catch (Exception e) {
            traceLogger.log("ERROR", "❌ 全局大盘初始化异常: " + e.getMessage());
        }

        traceLogger.log("INFO", "✨ OpenRouter 核心运行状态同步完毕，准备上线接收流量！");
    }

    private long parseLongSafely(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return 0L;
    }

    private double parseDoubleSafely(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return 0.0;
    }
}
