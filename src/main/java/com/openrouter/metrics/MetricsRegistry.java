package com.openrouter.metrics;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 唯一的 JVM 全局内存中心。存放并实时同步所有 Model 的监控状态。
 * 并附带调度了非常轻量的“指数健康回血”循环心跳。
 */
@Component
@EnableScheduling
public class MetricsRegistry {

    private final Map<String, ModelMetrics> metricsMap = new ConcurrentHashMap<>();

    public ModelMetrics getMetrics(String channelId) {
        return metricsMap.computeIfAbsent(channelId, k -> new ModelMetrics(channelId));
    }

    public Map<String, ModelMetrics> getAllMetrics() {
        return metricsMap;
    }

    /**
     * Spring 轻松定时任务。
     * 每 60 秒（30000 毫秒）执行一次。
     * 强行把所有目前在暗处默默报错的模型的 errorCount 除以 2！
     * 这完全实现了“只要别一直崩溃，就随着时间让你自然翻篇重来” 的终极自适应逻辑。
     */
    @Scheduled(fixedRate = 30000)
    public void scheduleHealthDecay() {
        for (ModelMetrics metrics : metricsMap.values()) {
            metrics.decayErrors();
        }
    }
}
