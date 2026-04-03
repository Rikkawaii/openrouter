package com.openrouter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 每日大盘数据统计服务。
 * 负责定时聚合 request_log 数据并存入 daily_stats 表。
 */
@Slf4j
@Service
public class DailyStatsService {

    private final JdbcTemplate jdbcTemplate;

    public DailyStatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 聚合指定日期的统计数据。
     */
    public void syncDailyStats(LocalDate date) {
        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        log.info("📊 正在统计日期 {} 的大盘数据...", dateStr);

        try {
            // 🔍 聚合当天数据
            Map<String, Object> results = jdbcTemplate.queryForMap("""
                SELECT 
                    AVG(total_duration_ms) as avg_duration,
                    COUNT(*) as total_requests,
                    SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) as failed_requests,
                    SUM(prompt_tokens) as prompt_tokens,
                    SUM(completion_tokens) as completion_tokens,
                    SUM(total_tokens) as total_tokens
                FROM request_log
                WHERE date(created_at) = ?
                """, dateStr);

            Long totalRequests = ((Number) results.getOrDefault("total_requests", 0)).longValue();
            if (totalRequests == 0) {
                log.info("ℹ️ 日期 {} 无任何请求记录，跳过统计", dateStr);
                return;
            }

            // 💾 写入或覆盖 daily_stats 表
            jdbcTemplate.update("""
                INSERT OR REPLACE INTO daily_stats 
                (stat_date, avg_duration, total_requests, failed_requests, prompt_tokens, completion_tokens, total_tokens, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now', 'localtime'))
                """,
                dateStr,
                results.get("avg_duration"),
                totalRequests,
                ((Number) results.getOrDefault("failed_requests", 0)).longValue(),
                ((Number) results.getOrDefault("prompt_tokens", 0)).longValue(),
                ((Number) results.getOrDefault("completion_tokens", 0)).longValue(),
                ((Number) results.getOrDefault("total_tokens", 0)).longValue()
            );
            log.info("✅ 日期 {} 的大盘统计更新完毕 (总请求: {})", dateStr, totalRequests);
        } catch (Exception e) {
            log.error("Failed to sync daily stats for {}: {}", dateStr, e.getMessage());
        }
    }

    /**
     * 每天凌晨 0 点执行：统计前一天的全天数据。
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void scheduledSync() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("⏰ 到达午夜，开始自动执行昨日数据归档...");
        syncDailyStats(yesterday);
    }

    /**
     * 启动时自检：如果前一天的数据没有记录，立刻进行一次统计。
     * 同时也支持统计更久之前的缺失数据（可选）。
     */
    public synchronized void checkAndSyncMissing() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String dateStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE);
        
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM daily_stats WHERE stat_date = ?", Integer.class, dateStr);
        if (count == null || count == 0) {
            log.info("🔄 启动自检检测：发现昨日 ({}) 数据缺失，触发自动补录...", dateStr);
            syncDailyStats(yesterday);
        } else {
            log.info("✅ 启动自检检测：昨日数据已归档，无需重复统计");
        }
    }
}
