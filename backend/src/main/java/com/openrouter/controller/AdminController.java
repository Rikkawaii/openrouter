package com.openrouter.controller;

import com.openrouter.config.ChannelConfig;
import com.openrouter.config.ChannelConfigStore;
import com.openrouter.config.RouterProperties;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.adapter.impl.DynamicModelRoutingStrategy;
import com.openrouter.metrics.ModelMetrics;
import com.openrouter.trace.TraceLogger;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
    private final ChannelConfigStore configStore;
    private final MetricsRegistry metricsRegistry;
    private final DynamicModelRoutingStrategy routingStrategy;
    private final JdbcTemplate jdbcTemplate;
    private final TraceLogger traceLogger;

    public AdminController(RouterProperties routerProperties, ChannelConfigStore configStore,
            MetricsRegistry metricsRegistry, DynamicModelRoutingStrategy routingStrategy,
            JdbcTemplate jdbcTemplate, TraceLogger traceLogger) {
        this.routerProperties = routerProperties;
        this.configStore = configStore;
        this.metricsRegistry = metricsRegistry;
        this.routingStrategy = routingStrategy;
        this.jdbcTemplate = jdbcTemplate;
        this.traceLogger = traceLogger;
    }

    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        boolean success = routerProperties.getAdminPassword() != null && routerProperties.getAdminPassword().equals(password);
        if (success) {
            return Mono.just(Map.of("success", true, "token", password));
        } else {
            return Mono.just(Map.of("success", false, "message", "密码错误"));
        }
    }

    @GetMapping("/verify")
    public Mono<Map<String, Object>> verify() {
        // 如果能走到这里，说明 Filter 校验通过（或者 Filter 还没拦截）
        // 配合 Filter 使用
        return Mono.just(Map.of("success", true));
    }

    @GetMapping("/dashboard")
    public DashboardResponse getDashboardData() {
        List<ChannelView> channelViews = new ArrayList<>();
        List<ChannelConfig> channels = configStore.getChannels();

        long globalTotalTokens = 0;
        long globalPromptTokens = 0;
        long globalCompletionTokens = 0;
        int activeCount = 0;

        for (ChannelConfig channel : channels) {
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

        long totalRequests = metricsRegistry.getGlobalTotalRequests();
        long successRequests = metricsRegistry.getGlobalSuccessCount();
        long failedRequests = Math.max(0, totalRequests - successRequests);

        GlobalStats stats = GlobalStats.builder()
                .totalChannels(channels.size())
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

    // ==================== 渠道/模型配置（channels.json 全量读写） ====================

    /**
     * 全量读取当前配置。API Key 与登录密码以掩码返回，不回传明文。
     */
    @GetMapping("/channels-config")
    public ChannelConfigStore.ConfigFile getChannelsConfig() {
        ChannelConfigStore.ConfigFile snapshot = configStore.snapshot();
        ChannelConfigStore.ConfigFile masked = new ChannelConfigStore.ConfigFile();
        masked.setSettings(maskSettings(snapshot.getSettings()));

        for (ChannelConfig ch : snapshot.getChannels()) {
            ChannelConfig copy = new ChannelConfig();
            copy.setId(ch.getId());
            copy.setType(ch.getType());
            copy.setBaseUrl(ch.getBaseUrl());
            copy.setApiKey(maskApiKey(ch.getApiKey()));
            copy.setBaseWeight(ch.getBaseWeight());
            copy.setEnabled(ch.isEnabled());
            copy.setModels(ch.getModels());
            masked.getChannels().add(copy);
        }
        masked.getModels().addAll(snapshot.getModels());
        return masked;
    }

    /**
     * 全量替换配置：校验 -> 原子写入 channels.json -> 立即生效（无需重启）。
     * 提交上来的 apiKey 若为掩码或留空，表示"保持原值"；settings 为 null 时保留现有基础设置。
     */
    @PutMapping("/channels-config")
    public ResponseEntity<Map<String, Object>> putChannelsConfig(
            @RequestBody ChannelConfigStore.ConfigFile body) {
        try {
            List<ChannelConfig> channels = body.getChannels() != null ? body.getChannels() : new ArrayList<>();
            List<ChannelConfig> resolved = new ArrayList<>(channels.size());

            for (ChannelConfig ch : channels) {
                String submittedKey = ch.getApiKey();
                if (submittedKey == null || submittedKey.isBlank() || submittedKey.contains("****")) {
                    ChannelConfig existing = findById(configStore.getChannels(), ch.getId());
                    if (existing != null) {
                        ch.setApiKey(existing.getApiKey());
                    } else {
                        throw new IllegalArgumentException("新渠道 " + ch.getId() + " 需要填写完整的 API Key");
                    }
                }
                resolved.add(ch);
            }

            ChannelConfigStore.Settings settings = resolveSettings(body.getSettings());
            configStore.replaceAll(resolved, body.getModels(), settings);
            traceLogger.log("INFO", String.format("💾 渠道配置已更新并持久化: %d 个渠道, %d 个模型",
                    resolved.size(), body.getModels() != null ? body.getModels().size() : 0));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("保存渠道配置失败", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "保存失败: " + e.getMessage()));
        }
    }

    // ==================== 基础设置（网关鉴权 / 登录密码 / 导师模型） ====================

    @GetMapping("/basic-settings")
    public ChannelConfigStore.Settings getBasicSettings() {
        return maskSettings(configStore.snapshot().getSettings());
    }

    /**
     * 更新基础设置。掩码/留空的 apiKey、adminPassword 表示保持原值；
     * 关闭鉴权时可无需 key。保存后写入 channels.json 并即时生效。
     */
    @PutMapping("/basic-settings")
    public ResponseEntity<Map<String, Object>> putBasicSettings(
            @RequestBody ChannelConfigStore.Settings body) {
        try {
            ChannelConfigStore.Settings resolved = resolveSettings(body);
            configStore.updateSettings(resolved);
            traceLogger.log("INFO", String.format("⚙️ 基础设置已更新（鉴权: %s）",
                    Boolean.TRUE.equals(resolved.getApiKeyEnabled()) ? "已启用" : "无需 key"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("保存基础设置失败", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "保存失败: " + e.getMessage()));
        }
    }

    /** 掩码解析：掩码/留空的敏感字段回退为当前有效值，并做一致性校验 */
    private ChannelConfigStore.Settings resolveSettings(ChannelConfigStore.Settings submitted) {
        if (submitted == null) return null;
        ChannelConfigStore.Settings cur = configStore.snapshot().getSettings();
        ChannelConfigStore.Settings out = new ChannelConfigStore.Settings();

        out.setApiKeyEnabled(submitted.getApiKeyEnabled() != null ? submitted.getApiKeyEnabled() : cur.getApiKeyEnabled());

        String key = submitted.getApiKey();
        out.setApiKey((key == null || key.isBlank() || key.contains("****"))
                ? configStore.effectiveApiKey() : key.trim());

        String pw = submitted.getAdminPassword();
        out.setAdminPassword((pw == null || pw.isBlank() || pw.contains("****"))
                ? configStore.effectiveAdminPassword() : pw.trim());

        out.setMentorModel(submitted.getMentorModel() != null ? submitted.getMentorModel().trim() : cur.getMentorModel());

        if (Boolean.TRUE.equals(out.getApiKeyEnabled())
                && (out.getApiKey() == null || out.getApiKey().isBlank())) {
            throw new IllegalArgumentException("已启用 API Key 鉴权，请填写密钥，或选择「无需 key」");
        }
        if (out.getAdminPassword() == null || out.getAdminPassword().isBlank()) {
            throw new IllegalArgumentException("管理页登录密码不能为空");
        }
        return out;
    }

    /** 复制一份设置并把敏感字段掩码掉（对外永不回传明文），用于返回 */
    private ChannelConfigStore.Settings maskSettings(ChannelConfigStore.Settings s) {
        ChannelConfigStore.Settings masked = new ChannelConfigStore.Settings();
        masked.setApiKeyEnabled(s.getApiKeyEnabled());
        masked.setApiKey(maskApiKey(configStore.effectiveApiKey()));
        masked.setAdminPassword(maskApiKey(configStore.effectiveAdminPassword()));
        masked.setMentorModel(configStore.effectiveMentorModel());
        return masked;
    }

    @PostMapping("/channels/{id}/toggle")
    public Mono<String> toggleChannel(@PathVariable String id) {
        return Mono.fromCallable(() -> {
                    boolean found = configStore.toggleChannel(id);
                    if (found) {
                        configStore.getChannels().stream()
                                .filter(c -> c.getId().equals(id))
                                .findFirst()
                                .ifPresent(c -> traceLogger.log("INFO",
                                        String.format("🔌 渠道 %s [%s]", id, c.isEnabled() ? "已开启" : "已关闭")));
                        return "SUCCESS";
                    }
                    return "NOT_FOUND";
                })
                .onErrorReturn("FAIL")
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/channels/{id}/weight/{val}")
    public Mono<String> setWeight(@PathVariable String id, @PathVariable int val) {
        return Mono.fromCallable(() -> {
                    boolean found = configStore.setWeight(id, val);
                    if (found) {
                        traceLogger.log("INFO", String.format("⚖️ 渠道 %s 权重已调整为: %d", id, val));
                        return "SUCCESS";
                    }
                    return "NOT_FOUND";
                })
                .onErrorReturn("FAIL")
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String maskApiKey(String key) {
        if (key == null || key.isBlank()) return "";
        return key.length() <= 4 ? "****" : "****" + key.substring(key.length() - 4);
    }

    private ChannelConfig findById(List<ChannelConfig> channels, String id) {
        return channels.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElse(null);
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

            List<ChannelConfig> channels = configStore.getChannels();
            return GlobalStats.builder()
                    .globalTotalRequests(accumulator.totalRequests)
                    .globalFailedRequests(accumulator.failedRequests)
                    .globalTotalTokens(accumulator.totalTokens)
                    .globalPromptTokens(accumulator.promptTokens)
                    .globalCompletionTokens(accumulator.completionTokens)
                    .avgResponseTime(accumulator.getFinalAvg())
                    .totalChannels(channels.size())
                    .activeChannels((int) channels.stream().filter(ChannelConfig::isEnabled).count())
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> fetchLogStats(String start, String end) {
        // 平均耗时只统计成功请求，与实时大盘、渠道卡片的口径保持一致
        String sql = "SELECT " +
                "AVG(CASE WHEN success = 1 THEN total_duration_ms END) as avg_duration, " +
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
