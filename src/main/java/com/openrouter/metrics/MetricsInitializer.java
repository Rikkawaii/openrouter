package com.openrouter.metrics;

import com.openrouter.config.RouterProperties;
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

    public MetricsInitializer(JdbcTemplate jdbcTemplate, MetricsRegistry metricsRegistry,
            RouterProperties routerProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.metricsRegistry = metricsRegistry;
        this.routerProperties = routerProperties;
    }

    @Override
    public void run(String... args) {
        log.info("🚀 启动环节：正在从 SQLite 恢复状态和历史账单...");
        List<RouterProperties.Channel> channels = routerProperties.getChannels();
        if (channels == null || channels.isEmpty())
            return;

        // 【步骤 1】恢复各渠道在管理后台调整过的开关和权重
        for (RouterProperties.Channel channel : channels) {
            String sql = "SELECT enabled, base_weight FROM channel_config WHERE channel_id = ?";
            List<Map<String, Object>> configs = jdbcTemplate.queryForList(sql, channel.getId());
            if (!configs.isEmpty()) {
                Map<String, Object> conf = configs.get(0);
                Integer bw = (Integer) conf.get("base_weight");
                Object en = conf.get("enabled");

                if (bw != null)
                    channel.setBaseWeight(bw);
                if (en != null) {
                    if (en instanceof Number) {
                        channel.setEnabled(((Number) en).intValue() == 1);
                    } else if (en instanceof Boolean) {
                        channel.setEnabled((Boolean) en);
                    }
                }
            }
        }
        log.info("✅ 第一步：渠道控制台动态配置 (开关、权重) 预热完毕");

        // 【步骤 2】恢复全生命周期内（全量历史） Token 分模型账单
        String statsSql = "SELECT channel_id, model, " +
                "SUM(prompt_tokens) as p, SUM(completion_tokens) as c, " +
                "SUM(CASE WHEN success=0 THEN 1 ELSE 0 END) as err, COUNT(1) as calls " +
                "FROM usage_log GROUP BY channel_id, model";
        List<Map<String, Object>> stats = jdbcTemplate.queryForList(statsSql);

        for (Map<String, Object> row : stats) {
            String ch = (String) row.get("channel_id");
            String mod = (String) row.get("model");

            long p = parseLongSafely(row.get("p"));
            long c = parseLongSafely(row.get("c"));
            long errCount = parseLongSafely(row.get("err"));
            long totalCalls = parseLongSafely(row.get("calls"));

            ModelMetrics metrics = metricsRegistry.getMetrics(ch);
            if (metrics != null) {
                // 这个方法自带分模型累加和总模型向上累加功能，一箭双雕
                metrics.addTokens(mod, p, c);
                for (int i = 0; i < errCount; i++)
                    metrics.recordError();
                for (int i = 0; i < totalCalls; i++)
                    metrics.recordCall();
            }
        }
        log.info("✅ 第二步：全局历史账单聚合回填内存处理完毕");

        // 【步骤 3】计算近期均值填充实时大盘的 Avg TTFT 雷达和 Total Duration 雷达
        for (RouterProperties.Channel channel : channels) {
            String ttftSql = "SELECT AVG(NULLIF(ttft_ms, 0)) as avg_ttft, AVG(total_duration_ms) as avg_dur FROM (" +
                             "SELECT ttft_ms, total_duration_ms FROM usage_log WHERE channel_id = ? AND success = 1 " +
                             "ORDER BY created_at DESC LIMIT 100)";
            List<Map<String, Object>> ttftResult = jdbcTemplate.queryForList(ttftSql, channel.getId());
            if (!ttftResult.isEmpty()) {
                Map<String, Object> result = ttftResult.get(0);
                if (result.get("avg_ttft") != null) {
                    Number avgTTFT = (Number) result.get("avg_ttft");
                    metricsRegistry.getMetrics(channel.getId()).recordLatency(avgTTFT.longValue());
                }
                if (result.get("avg_dur") != null) {
                    Number avgDur = (Number) result.get("avg_dur");
                    metricsRegistry.getMetrics(channel.getId()).recordTotalDuration(avgDur.longValue());
                }
            }
        }
        log.info("✅ 第三步：短效延迟监控 (Avg TTFT) 历史预热完成，目前大盘已完全回归正常工作状态。");
    }

    private long parseLongSafely(Object val) {
        if (val == null)
            return 0L;
        if (val instanceof Number)
            return ((Number) val).longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (Exception e) {
            return 0L;
        }
    }
}
