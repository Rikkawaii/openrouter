package com.openrouter.metrics;

import com.openrouter.config.RouterProperties;
import com.openrouter.config.RoutingConfig;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 唯一的 JVM 全局内存中心。存放并实时同步所有渠道的监控状态。
 * <p>
 * 健康度衰减由 {@link ModelMetrics} 内部按时间惰性完成（半衰期可配），
 * 因此这里不再需要定时任务；{@code @EnableScheduling} 保留给每日统计等定时作业。
 */
@Component
@EnableScheduling
public class MetricsRegistry {

    private final RouterProperties routerProperties;

    private final Map<String, ModelMetrics> metricsMap = new ConcurrentHashMap<>();

    // =============== 全局大盘指标 (缓存自启动初始化后的实时增量) ===============
    // 注意：使用增量加权平均公式维护，无需保留原始 sum，避免大数溢出
    private volatile long globalAvgResponseTime = 0;        // 仅成功请求的平均响应时间 (ms)
    private final AtomicLong globalSuccessCount = new AtomicLong(0); // 仅成功
    private final AtomicLong globalTotalRequests = new AtomicLong(0); // 所有请求 (含失败)

    public MetricsRegistry(RouterProperties routerProperties) {
        this.routerProperties = routerProperties;
    }

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
        return metricsMap.computeIfAbsent(channelId, k -> new ModelMetrics(
                k,
                () -> routing().getEwmaAlpha(),
                () -> routing().getErrorDecaySeconds(),
                () -> routing().getErrorDecayFactor()));
    }

    public Map<String, ModelMetrics> getAllMetrics() {
        return metricsMap;
    }

    /** 运行中热更参数后立即生效；配置缺失时回落到默认值 */
    private RoutingConfig routing() {
        RoutingConfig cfg = routerProperties.getRouting();
        return cfg != null ? cfg : new RoutingConfig();
    }
}
