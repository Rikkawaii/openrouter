package com.openrouter.metrics;

import com.openrouter.config.ChannelConfig;
import com.openrouter.config.ChannelConfigStore;
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
    private final ChannelConfigStore configStore;
    private final DailyStatsService dailyStatsService;
    private final com.openrouter.trace.TraceLogger traceLogger;

    public MetricsInitializer(JdbcTemplate jdbcTemplate, MetricsRegistry metricsRegistry,
            ChannelConfigStore configStore, DailyStatsService dailyStatsService,
            com.openrouter.trace.TraceLogger traceLogger) {
        this.jdbcTemplate = jdbcTemplate;
        this.metricsRegistry = metricsRegistry;
        this.configStore = configStore;
        this.dailyStatsService = dailyStatsService;
        this.traceLogger = traceLogger;
    }

    @Override
    public void run(String... args) {
        List<ChannelConfig> channels = configStore.getChannels();
        if (channels == null || channels.isEmpty()) {
            traceLogger.log("WARN", "⚠️ 未发现任何配置通道，跳过初始化。");
            return;
        }

        // --- 核心段落 A: 预热通道健康指标 (Token、成功率、延迟 EMA) ---
        traceLogger.log("INFO", "🚀 [1/2] 系统启动：正在预热各通道历史健康指标 (Metrics Warmup)...");
        
        // 1. 恢复 Token 账单（排除未进入路由阶段的请求，其 channel_id 为空）
        String tokenSql = "SELECT channel_id, model, SUM(prompt_tokens) as p, SUM(completion_tokens) as c " +
                          "FROM request_log WHERE channel_id IS NOT NULL GROUP BY channel_id, model";
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
        for (ChannelConfig channel : channels) {
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

        // --- 核心段落 B: 全局大盘初始化 (补录缺失天的归档 + 初始化内存计数器) ---
        traceLogger.log("INFO", "🚀 [2/2] 系统启动：正在同步全局历史概览与大盘计数器...");
        dailyStatsService.checkAndSyncMissing();
        
        try {
            // 混合模式初始化统计 (daily_stats 历史 + 今日增量)
            Map<String, Object> history = jdbcTemplate.queryForMap("""
                SELECT SUM(total_requests) as total, SUM(total_requests - failed_requests) as succ,
                       SUM(avg_duration * (total_requests - failed_requests)) as dur_sum FROM daily_stats""");
            
            Map<String, Object> today = jdbcTemplate.queryForMap("""
                SELECT AVG(CASE WHEN success = 1 THEN total_duration_ms END) as avg, COUNT(*) as total,
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
