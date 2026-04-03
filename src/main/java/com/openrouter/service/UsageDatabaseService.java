package com.openrouter.service;

import com.openrouter.trace.RequestTraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 数据库存储层：负责将使用量数据（Request Log 和 Model Call Log）持久化到 SQLite。
 */
@Slf4j
@Service
public class UsageDatabaseService {

    private final JdbcTemplate jdbcTemplate;

    public UsageDatabaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initDb() {
        log.info("🏠 正在初始化使用量数据库表 (SQLite)...");
        
        // 核心表：记录最终交付给用户的请求全链路数据
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS request_log (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        trace_id         VARCHAR(32),
                        channel_id       VARCHAR(64),
                        model            VARCHAR(128),
                        prompt_tokens    INTEGER,
                        completion_tokens INTEGER,
                        total_tokens     INTEGER,
                        total_duration_ms INTEGER,
                        retry_count      INTEGER DEFAULT 0,
                        trace_events     TEXT,
                        full_request     TEXT,
                        full_response    TEXT,
                        success          BOOLEAN,
                        error_msg        TEXT,
                        created_at       DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                """);

        // 尝试表：记录每一次底层模型的调用细节（包含重试和失败）
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS model_call_log (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        trace_id         VARCHAR(32),
                        channel_id       VARCHAR(64),
                        model            VARCHAR(128),
                        duration_ms      INTEGER,
                        success          BOOLEAN,
                        error_msg        TEXT,
                        created_at       DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                """);

        // 配置缓存表：（可选，用于存储动态权重调整）
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS channel_config (
                        channel_id VARCHAR(64) PRIMARY KEY,
                        enabled BOOLEAN,
                        base_weight INTEGER
                    )
                """);

        // 每日统计表：用于展示大盘趋势
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS daily_stats (
                        stat_date        DATE PRIMARY KEY,
                        avg_duration     DOUBLE,
                        prompt_tokens    BIGINT,
                        completion_tokens BIGINT,
                        total_tokens     BIGINT,
                        failed_requests  BIGINT,
                        total_requests   BIGINT,
                        updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                """);

        // 🟢 性能索引：确保大数据量下查询不产生全表扫描
        log.info("⚡ 正在优化数据库索引...");
        // 1. request_log: 用于 Admin Dashboard 的各种时间范围聚合 (BETWEEN)
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_request_created_at ON request_log(created_at)");
        
        // 2. model_call_log: 强大的复合索引，同时满足以下两个关键路径：
        //   - 路径 A: 启动预热 Step 3 (按 channel_id, success 过滤并按 created_at 排序取 100 条)
        //   - 路径 B: 启动预热 Step 2.5 (按 channel_id 分组统计错误率)
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_call_composite_stats ON model_call_log(channel_id, success, created_at)");
    }

    /**
     * 异步保存最终请求日志。
     */
    public void saveRequestLogAsync(RequestTraceContext ctx,
                                    boolean success, String errorMsg) {
        if (ctx == null)
            return;
        Mono.fromRunnable(() -> {
            try {
                String sql = "INSERT INTO request_log " +
                        "(trace_id, channel_id, model, prompt_tokens, completion_tokens, total_tokens, " +
                        "total_duration_ms, retry_count, trace_events, full_request, full_response, " +
                        "success, error_msg, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', 'localtime'))";

                jdbcTemplate.update(sql,
                        ctx.getTraceId(),
                        ctx.getChannelId(), ctx.getModel(),
                        ctx.getPromptTokens(), ctx.getCompletionTokens(),
                        ctx.getPromptTokens() + ctx.getCompletionTokens(),
                        ctx.getTotalDurationMs(),
                        ctx.getRetryCount(),
                        ctx.toEventsJson(),
                        ctx.getFullRequestJson(),
                        ctx.getFullResponseJson(),
                        success, errorMsg);
            } catch (Exception e) {
                log.error("Failed to persist request log to DB: {}", e.getMessage());
            }
        })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * 异步保存单次模型尝试日志。
     */
    public void saveCallLogAsync(RequestTraceContext ctx, String channelId, String model,
                                 long durationMs, boolean success, String errorMsg) {
        if (ctx == null) return;
        Mono.fromRunnable(() -> {
            try {
                String sql = "INSERT INTO model_call_log " +
                        "(trace_id, channel_id, model, duration_ms, success, error_msg, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, datetime('now', 'localtime'))";
                jdbcTemplate.update(sql, ctx.getTraceId(), channelId, model, durationMs, success, errorMsg);
            } catch (Exception e) {
                log.error("Failed to persist model call log to DB: {}", e.getMessage());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe();
    }
}
