package com.openrouter.controller;

import com.openrouter.config.RouterProperties;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.adapter.impl.DynamicModelRoutingStrategy;
import com.openrouter.metrics.ModelMetrics;
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

    public AdminController(RouterProperties routerProperties, MetricsRegistry metricsRegistry,
            DynamicModelRoutingStrategy routingStrategy, JdbcTemplate jdbcTemplate) {
        this.routerProperties = routerProperties;
        this.metricsRegistry = metricsRegistry;
        this.routingStrategy = routingStrategy;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/dashboard")
    public DashboardResponse getDashboardData() {
        List<ChannelView> channelViews = new ArrayList<>();
        List<RouterProperties.Channel> channels = routerProperties.getChannels();

        int channelsWithSamples = 0;
        long globalTotalTokens = 0;
        long globalPromptTokens = 0;
        long globalCompletionTokens = 0;
        long globalTotalCalls = 0;
        long globalErrors = 0;
        int activeCount = 0;
        long latencySum = 0;

        if (channels != null) {
            for (RouterProperties.Channel channel : channels) {
                ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
                long avgLat = metrics.getAverageLatencyMs();

                ChannelView view = ChannelView.builder()
                        .id(channel.getId())
                        .type(channel.getType())
                        .baseUrl(channel.getBaseUrl())
                        .models(channel.getModels() != null ? String.join(", ", channel.getModels()) : "")
                        .enabled(channel.isEnabled())
                        .baseWeight(channel.getBaseWeight())
                        .avgLatencyMs(avgLat)
                        .avgTotalDurationMs(metrics.getAverageTotalDurationMs())
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
                globalTotalCalls += metrics.getTotalCalls();
                globalErrors += metrics.getErrorCount();
                if (channel.isEnabled())
                    activeCount++;

                // 只有产生过延迟采样的渠道，才计入大盘平均分，避免被初始 0 值带偏
                if (avgLat > 0) {
                    latencySum += avgLat;
                    channelsWithSamples++;
                }
            }
        }

        GlobalStats stats = GlobalStats.builder()
                .totalChannels(channels == null ? 0 : channels.size())
                .activeChannels(activeCount)
                .globalTotalTokens(globalTotalTokens)
                .globalPromptTokens(globalPromptTokens)
                .globalCompletionTokens(globalCompletionTokens)
                .globalTotalCalls(globalTotalCalls)
                .globalErrors(globalErrors)
                .avgGlobalLatency(channelsWithSamples > 0 ? (latencySum / channelsWithSamples) : 0)
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
                log.error("Failed to update channel_config for {}", channel.getId(), e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    @GetMapping("/stats/range")
    public Mono<List<Map<String, Object>>> getRangeStats(
            @RequestParam String start,
            @RequestParam String end) {
        // SQLite datetime('now', 'localtime') outputs format 'YYYY-MM-DD HH:MM:SS'
        // Spring's incoming ISO string has a 'T', which is mathematically > ' ', causing BETWEEN queries to fail.
        String startStr = start.replace("T", " ");
        String endStr = end.replace("T", " ");

        String sql = "SELECT channel_id as channelId, model, " +
                     "SUM(prompt_tokens) as p, " +
                     "SUM(completion_tokens) as c, " +
                     "SUM(total_tokens) as total, " +
                     "COUNT(1) as totalCalls, " +
                     "SUM(CASE WHEN success=1 THEN 1 ELSE 0 END) as successCount, " +
                     "SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) as errorCount, " +
                     "AVG(NULLIF(ttft_ms, 0)) as avgLat, " +
                     "AVG(total_duration_ms) as avgDur " +
                     "FROM usage_log " +
                     "WHERE created_at BETWEEN ? AND ? " +
                     "GROUP BY channel_id, model";
                     
        return Mono.fromCallable(() -> jdbcTemplate.queryForList(sql, startStr, endStr))
                   .subscribeOn(Schedulers.boundedElastic());
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
        private long globalTotalCalls;
        private long globalErrors;
        private long avgGlobalLatency;
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
        private long avgLatencyMs;
        private long avgTotalDurationMs;
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
