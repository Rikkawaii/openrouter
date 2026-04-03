package com.openrouter.metrics;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 唯一的 JVM 全局内存中心。存放并实时同步所有 Model 的监控状态。
 * 并附带调度了非常轻量的“指数健康回血”循环心跳。
 */
@Component
@EnableScheduling
public class MetricsRegistry {

    private final Map<String, ModelMetrics> metricsMap = new ConcurrentHashMap<>();

    // =============== 全局大盘指标 (缓存自启动初始化后的实时增量) ===============
    // 注意：使用增量加权平均公式维护，无需保留原始 sum，避免大数溢出
    private volatile long globalAvgResponseTime = 0;        // 仅成功请求的平均响应时间 (ms)
    private final AtomicLong globalSuccessCount = new AtomicLong(0); // 仅成功
    private final AtomicLong globalTotalRequests = new AtomicLong(0); // 所有请求 (含失败)

    public void initGlobalStats(long initialAvgResponseTime, long initialSuccessCount, long initialTotalRequests) {
        this.globalAvgResponseTime = initialAvgResponseTime;
        this.globalSuccessCount.set(initialSuccessCount);
        this.globalTotalRequests.set(initialTotalRequests);
    }

    public synchronized void recordGlobalResponse(long durationMs, boolean success) {
        // 无论成功失败，都算一次全局请求
        globalTotalRequests.incrementAndGet();
        
        if (success) {
            long count = globalSuccessCount.get();
            // 增量加权平均：newAvg = (oldAvg * count + newValue) / (count + 1)
            globalAvgResponseTime = (globalAvgResponseTime * count + durationMs) / (count + 1);
            globalSuccessCount.incrementAndGet();
        }
    }

    public long getGlobalAverageResponseTime() {
        return globalAvgResponseTime;
    }

    public long getGlobalTotalRequests() {
        return globalTotalRequests.get();
    }

    public long getGlobalSuccessCount() {
        return globalSuccessCount.get();
    }

    public ModelMetrics getMetrics(String channelId) {
        return metricsMap.computeIfAbsent(channelId, k -> new ModelMetrics(channelId));
    }

    public Map<String, ModelMetrics> getAllMetrics() {
        return metricsMap;
    }

    /**
     * Spring 轻松定时任务。
     * 每 60 秒（60000 毫秒）执行一次。
     * 强行把所有目前在暗处默默报错的模型的 errorCount 除以 2！
     * 这完全实现了“只要别一直崩溃，就随着时间让你自然翻篇重来” 的终极自适应逻辑。
     */
    @Scheduled(fixedRate = 60000)
    public void scheduleHealthDecay() {
        for (ModelMetrics metrics : metricsMap.values()) {
            metrics.decayErrors();
        }
    }
}
