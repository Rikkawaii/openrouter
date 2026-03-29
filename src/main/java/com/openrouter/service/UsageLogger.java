package com.openrouter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
public class UsageLogger {

    private final JdbcTemplate jdbcTemplate;

    public UsageLogger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initDb() {
        log.info("初始化 SQLite 数据表...");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS usage_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                channel_id VARCHAR(64),
                model VARCHAR(128),
                prompt_tokens INTEGER,
                completion_tokens INTEGER,
                total_tokens INTEGER,
                ttft_ms INTEGER,
                total_duration_ms INTEGER,
                success BOOLEAN,
                error_msg TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS channel_config (
                channel_id VARCHAR(64) PRIMARY KEY,
                enabled BOOLEAN,
                base_weight INTEGER
            )
        """);
    }

    /**
     * 异步入库，彻底释放核心线程池
     */
    public void recordLogAsync(String channelId, String model, long promptTokens, long completionTokens,
                               long ttftMs, long totalDurationMs, boolean success, String errorMsg) {
        Mono.fromRunnable(() -> {
            try {
                String sql = "INSERT INTO usage_log (channel_id, model, prompt_tokens, completion_tokens, total_tokens, ttft_ms, total_duration_ms, success, error_msg, created_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', 'localtime'))";
                jdbcTemplate.update(sql, channelId, model, promptTokens, completionTokens, promptTokens + completionTokens, ttftMs, totalDurationMs, success, errorMsg);
            } catch (Exception e) {
                log.error("Failed to insert usage log for channel {}: {}", channelId, e.getMessage());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe();
    }
}
