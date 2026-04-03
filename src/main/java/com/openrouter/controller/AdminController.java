package com.openrouter.controller;

import com.openrouter.config.RouterProperties;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.adapter.impl.DynamicModelRoutingStrategy;
import com.openrouter.metrics.ModelMetrics;
import com.openrouter.trace.TraceLogger;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private final RouterProperties routerProperties;
    private final MetricsRegistry metricsRegistry;
    private final DynamicModelRoutingStrategy routingStrategy;
    private final JdbcTemplate jdbcTemplate;
    private final TraceLogger traceLogger;

    public AdminController(RouterProperties routerProperties, MetricsRegistry metricsRegistry,
            DynamicModelRoutingStrategy routingStrategy, JdbcTemplate jdbcTemplate, TraceLogger traceLogger) {
        this.routerProperties = routerProperties;
        this.metricsRegistry = metricsRegistry;
        this.routingStrategy = routingStrategy;
        this.jdbcTemplate = jdbcTemplate;
        this.traceLogger = traceLogger;
    }

    @GetMapping("/dashboard")
    public DashboardResponse getDashboardData() {
        List<ChannelView> channelViews = new ArrayList<>();
        List<RouterProperties.Channel> channels = routerProperties.getChannels();

        long globalTotalTokens = 0;
        long globalPromptTokens = 0;
        long globalCompletionTokens = 0;
        int activeCount = 0;

        if (channels != null) {
            for (RouterProperties.Channel channel : channels) {
                ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());

                ChannelView view = ChannelView.builder()
                        .id(channel.getId())
                        .type(channel.getType())
                        .baseUrl(channel.getBaseUrl())
                        .models(channel.getModels() != null ? String.join(", ", channel.getModels()) : "")
                        .enabled(channel.isEnabled())
                        .baseWeight(channel.getBaseWeight())
                        .avgModelLatencyMs(metrics.getAverageModelLatencyMs())
                        .errorCount(metrics.getErrorCount())
                        .currentConcurrentCalls(metrics.getCurrentConcurrentCalls())
                        .totalTokensUsed(metrics.getTotalTokensUsed())
                        .promptTokensUsed(metrics.getPromptTokensUsed())
                        .completionTokensUsed(metrics.getCompletionTokensUsed())
                        .totalCalls(metrics.getTotalCalls())
                        .currentScore(routingStrategy.calculateScore(channel))
                        .tokensByModel(metrics.getTokensByModel())
                        .build();

                channelViews.add(view);

                // 大盘汇算
                globalTotalTokens += metrics.getTotalTokensUsed();
                globalPromptTokens += metrics.getPromptTokensUsed();
                globalCompletionTokens += metrics.getCompletionTokensUsed();
                if (channel.isEnabled())
                    activeCount++;
            }
        }

        long totalRequests = metricsRegistry.getGlobalTotalRequests();
        long successRequests = metricsRegistry.getGlobalSuccessCount();
        long failedRequests = Math.max(0, totalRequests - successRequests);

        GlobalStats stats = GlobalStats.builder()
                .totalChannels(channels == null ? 0 : channels.size())
                .activeChannels(activeCount)
                .globalTotalTokens(globalTotalTokens)
                .globalPromptTokens(globalPromptTokens)
                .globalCompletionTokens(globalCompletionTokens)
                .globalTotalRequests(totalRequests)
                .globalFailedRequests(failedRequests)
                .avgResponseTime(metricsRegistry.getGlobalAverageResponseTime())
                .build();

        return DashboardResponse.builder()
                .globalStats(stats)
                .channels(channelViews)
                .build();
    }

    @PostMapping("/channels/{id}/toggle")
    public String toggleChannel(@PathVariable String id) {
        List<RouterProperties.Channel> channels = routerProperties.getChannels();
        if (channels == null)
            return "FAIL";
        for (RouterProperties.Channel channel : channels) {
            if (channel.getId().equals(id)) {
                channel.setEnabled(!channel.isEnabled());
                traceLogger.log("INFO", String.format("🔌 渠道 %s [%s]", id, channel.isEnabled() ? "已开启" : "已关闭"));
                updateChannelConfigAsync(channel);
                return "SUCCESS";
            }
        }
        return "NOT_FOUND";
    }

    @PutMapping("/channels/{id}/weight/{val}")
    public String setWeight(@PathVariable String id, @PathVariable int val) {
        List<RouterProperties.Channel> channels = routerProperties.getChannels();
        if (channels == null)
            return "FAIL";
        for (RouterProperties.Channel channel : channels) {
            if (channel.getId().equals(id)) {
                channel.setBaseWeight(val);
                traceLogger.log("INFO", String.format("⚖️ 渠道 %s 权重已调整为: %d", id, val));
                updateChannelConfigAsync(channel);
                return "SUCCESS";
            }
        }
        return "NOT_FOUND";
    }

    private void updateChannelConfigAsync(RouterProperties.Channel channel) {
        Mono.fromRunnable(() -> {
            try {
                String sql = "INSERT INTO channel_config (channel_id, enabled, base_weight) " +
                        "VALUES (?, ?, ?) " +
                        "ON CONFLICT(channel_id) DO UPDATE SET " +
                        "enabled=excluded.enabled, base_weight=excluded.base_weight";
                int enabledInt = channel.isEnabled() ? 1 : 0;
                jdbcTemplate.update(sql, channel.getId(), enabledInt, channel.getBaseWeight());
            } catch (Exception e) {
                traceLogger.log("ERROR", String.format("Failed to update channel_config for %s: %s", channel.getId(), e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    @GetMapping("/stats/range")
    public Mono<GlobalStats> getRangeStats(
            @RequestParam String start,
            @RequestParam String end) {
        return Mono.fromCallable(() -> {
            // ISO-8601 strings usually look like 2026-03-27T17:15:30
            // We want to slice this into calendar boundaries
            String startStr = start.replace("T", " ");
            String endStr = end.replace("T", " ");
            
            java.time.LocalDateTime startDt = java.time.LocalDateTime.parse(start.substring(0, 19));
            java.time.LocalDateTime endDt = java.time.LocalDateTime.parse(end.substring(0, 19));
            java.time.LocalDate dStart = startDt.toLocalDate();
            java.time.LocalDate dEnd = endDt.toLocalDate();

            StatAccumulator accumulator = new StatAccumulator();

            if (dStart.equals(dEnd)) {
                // 情况 1: 同一自然日内，直接查原始日志
                accumulator.addFromLogs(fetchLogStats(startStr, endStr));
            } else {
                // 情况 2: 跨天，分三段合并以利用 daily_stats 缓存
                
                // 1. 起始日残缺段 (start -> 23:59:59)
                String startDayEnd = dStart.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) + " 23:59:59";
                accumulator.addFromLogs(fetchLogStats(startStr, startDayEnd));

                // 2. 中间整日段 (dStart + 1 -> dEnd - 1)
                java.time.LocalDate midStart = dStart.plusDays(1);
                java.time.LocalDate midEnd = dEnd.minusDays(1);
                if (!midStart.isAfter(midEnd)) {
                    List<Map<String, Object>> dailyList = jdbcTemplate.queryForList(
                        "SELECT * FROM daily_stats WHERE stat_date BETWEEN ? AND ?", 
                        midStart.toString(), midEnd.toString());
                    for (Map<String, Object> day : dailyList) {
                        accumulator.addFromDaily(day);
                    }
                }

                // 3. 结束日头段 (00:00:00 -> end)
                String endDayStart = dEnd.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) + " 00:00:00";
                accumulator.addFromLogs(fetchLogStats(endDayStart, endStr));
            }

            return GlobalStats.builder()
                    .globalTotalRequests(accumulator.totalRequests)
                    .globalFailedRequests(accumulator.failedRequests)
                    .globalTotalTokens(accumulator.totalTokens)
                    .globalPromptTokens(accumulator.promptTokens)
                    .globalCompletionTokens(accumulator.completionTokens)
                    .avgResponseTime(accumulator.getFinalAvg())
                    .totalChannels(routerProperties.getChannels().size())
                    .activeChannels((int) routerProperties.getChannels().stream().filter(RouterProperties.Channel::isEnabled).count())
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> fetchLogStats(String start, String end) {
        String sql = "SELECT " +
                "AVG(total_duration_ms) as avg_duration, " +
                "SUM(prompt_tokens) as prompt_tokens, " +
                "SUM(completion_tokens) as completion_tokens, " +
                "SUM(total_tokens) as total_tokens, " +
                "COUNT(1) as total_requests, " +
                "SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) as failed_requests " +
                "FROM request_log " +
                "WHERE created_at BETWEEN ? AND ? ";
        return jdbcTemplate.queryForMap(sql, start, end);
    }

    private class StatAccumulator {
        long totalRequests = 0;
        long failedRequests = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        double weightedAvgSum = 0;
        long totalSuccessCount = 0;

        void addFromLogs(Map<String, Object> r) {
            long reqs = parseLongSafely(r.get("total_requests"));
            if (reqs == 0) return;
            
            long failed = parseLongSafely(r.get("failed_requests"));
            long success = Math.max(0, reqs - failed);
            long avg = parseLongSafely(r.get("avg_duration"));

            this.totalRequests += reqs;
            this.failedRequests += failed;
            this.promptTokens += parseLongSafely(r.get("prompt_tokens"));
            this.completionTokens += parseLongSafely(r.get("completion_tokens"));
            this.totalTokens += parseLongSafely(r.get("total_tokens"));
            
            if (success > 0) {
                this.weightedAvgSum += (avg * success);
                this.totalSuccessCount += success;
            }
        }

        void addFromDaily(Map<String, Object> r) {
            long reqs = parseLongSafely(r.get("total_requests"));
            long failed = parseLongSafely(r.get("failed_requests"));
            long success = Math.max(0, reqs - failed);
            long avg = parseLongSafely(r.get("avg_duration"));

            this.totalRequests += reqs;
            this.failedRequests += failed;
            this.promptTokens += parseLongSafely(r.get("prompt_tokens"));
            this.completionTokens += parseLongSafely(r.get("completion_tokens"));
            this.totalTokens += parseLongSafely(r.get("total_tokens"));

            if (success > 0) {
                this.weightedAvgSum += (avg * success);
                this.totalSuccessCount += success;
            }
        }

        long getFinalAvg() {
            return totalSuccessCount == 0 ? 0 : (long) (weightedAvgSum / totalSuccessCount);
        }
    }

    private long parseLongSafely(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    @Data
    @Builder
    public static class DashboardResponse {
        private GlobalStats globalStats;
        private List<ChannelView> channels;
    }

    @Data
    @Builder
    public static class GlobalStats {
        private int totalChannels;
        private int activeChannels;
        private long globalTotalTokens;
        private long globalPromptTokens;
        private long globalCompletionTokens;
        private long globalTotalRequests;
        private long globalFailedRequests;
        private long avgResponseTime;
    }

    @Data
    @Builder
    public static class ChannelView {
        private String id;
        private String type;
        private String baseUrl;
        private String models;
        private boolean enabled;
        private int baseWeight;
        private long avgModelLatencyMs;
        private long errorCount;
        private long currentConcurrentCalls;
        private long totalTokensUsed;
        private long promptTokensUsed;
        private long completionTokensUsed;
        private long totalCalls;
        private double currentScore;
        private Map<String, ModelMetrics.TokenPairView> tokensByModel;
    }
}
