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

        // 2. 恢复调用计数、错误计数与 (渠道, 模型) 级延迟/首包延迟
        long modelBuckets = 0;
        for (ChannelConfig channel : channels) {
            // 2.1 调用次数与失败计数
            String callSql = "SELECT COUNT(*) as calls, SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) as err " +
                             "FROM model_call_log WHERE channel_id = ?";
            Map<String, Object> calls = jdbcTemplate.queryForMap(callSql, channel.getId());
            ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
            if (metrics == null) continue;

            metrics.recordCalls(parseLongSafely(calls.get("calls")));
            metrics.recordErrors(parseLongSafely(calls.get("err")));

            // 2.2 按 (渠道, 模型) 取最近 100 条成功尝试，恢复耗时与首包延迟
            List<Map<String, Object>> perModel = jdbcTemplate.queryForList("""
                    SELECT model,
                           AVG(duration_ms) AS avg_dur,
                           COUNT(*)         AS samples,
                           AVG(ttft_ms)     AS avg_ttft,
                           SUM(CASE WHEN ttft_ms IS NOT NULL THEN 1 ELSE 0 END) AS ttft_samples
                    FROM (
                        SELECT model, duration_ms, ttft_ms,
                               ROW_NUMBER() OVER (PARTITION BY model ORDER BY id DESC) AS rn
                        FROM model_call_log
                        WHERE channel_id = ? AND success = 1
                    ) WHERE rn <= 100
                    GROUP BY model
                    """, channel.getId());

            long weightedLatencySum = 0;
            long latencySamples = 0;
            for (Map<String, Object> row : perModel) {
                String model = (String) row.get("model");
                long avgDur = parseLongSafely(row.get("avg_dur"));
                long samples = parseLongSafely(row.get("samples"));
                metrics.warmupLatency(model, avgDur, samples);
                long ttftSamples = parseLongSafely(row.get("ttft_samples"));
                if (ttftSamples > 0) {
                    // 历史行为 NULL，不参与均值；只有真正观测到首包的流式样本才预热
                    metrics.warmupTtft(model, parseLongSafely(row.get("avg_ttft")), ttftSamples);
                }
                if (avgDur > 0 && samples > 0) {
                    weightedLatencySum += avgDur * samples;
                    latencySamples += samples;
                }
                modelBuckets++;
            }
            if (latencySamples > 0) {
                metrics.warmupChannelLatency(weightedLatencySum / latencySamples, latencySamples);
            }

            // 2.3 恢复 (渠道, 模型) 的尝试次数，与渠道级 totalCalls 保持同口径
            List<Map<String, Object>> callsByModel = jdbcTemplate.queryForList(
                    "SELECT model, COUNT(*) AS calls FROM model_call_log WHERE channel_id = ? GROUP BY model",
                    channel.getId());
            for (Map<String, Object> row : callsByModel) {
                metrics.recordModelCalls((String) row.get("model"), parseLongSafely(row.get("calls")));
            }
        }
        traceLogger.log("INFO", String.format("✅ 各通道健康指标预热完成。恢复 Token: %d, (渠道,模型) 指标桶: %d",
                totalTokensRecovered, modelBuckets));

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
