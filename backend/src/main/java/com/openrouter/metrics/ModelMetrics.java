package com.openrouter.metrics;

import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 存储在 JVM 内存中的单个大模型厂商适配器的动态监控状态机。
 * 现已支持原子级（输入/输出）细分账本及分模型统计。
 */
public class ModelMetrics {

    private final String channelId;

    private volatile int baseWeight = 100;

    private final AtomicLong averageModelLatencyMs = new AtomicLong(0);
    private final AtomicLong latencySampleCount = new AtomicLong(0);

    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong recentErrorCount = new AtomicLong(0);

    private final LongAdder currentConcurrentCalls = new LongAdder();

    // =============== 账本统计指标 (全量) ===============
    private final AtomicLong totalTokensUsed = new AtomicLong(0);
    private final AtomicLong promptTokensUsed = new AtomicLong(0);
    private final AtomicLong completionTokensUsed = new AtomicLong(0);
    private final AtomicLong totalCalls = new AtomicLong(0);

    // =============== 账本统计指标 (分模型明细) ===============
    // 采用 TokenPair 存储每个模型的输入和输出指标
    private final ConcurrentHashMap<String, TokenPair> tokensByModel = new ConcurrentHashMap<>();

    public ModelMetrics(String channelId) {
        this.channelId = channelId;
    }

    /**
     * 原子级增加 Token (带模型名分账)
     */
    public void addTokens(String model, long prompt, long completion) {
        long total = prompt + completion;

        // 更新总体指标
        promptTokensUsed.addAndGet(prompt);
        completionTokensUsed.addAndGet(completion);
        totalTokensUsed.addAndGet(total);

        // 更新分模型分账
        if (model != null && !model.isBlank()) {
            TokenPair pair = tokensByModel.computeIfAbsent(model, k -> new TokenPair());
            pair.prompt.addAndGet(prompt);
            pair.completion.addAndGet(completion);
        }
    }

    /**
     * 兼容旧版或无法区分输入输出的场景数据恢复
     */
    // public void addTokens(String model, long tokens) {
    // totalTokensUsed.addAndGet(tokens);
    // if (model != null && !model.isBlank()) {
    // TokenPair pair = tokensByModel.computeIfAbsent(model, k -> new TokenPair());
    // // 默认全记入 prompt 保证总量对齐
    // pair.prompt.addAndGet(tokens);
    // }
    // }

    // public void addTokens(long tokens) {
    // totalTokensUsed.addAndGet(tokens);
    // }

    // public void addTokens(long prompt, long completion) {
    // promptTokensUsed.addAndGet(prompt);
    // completionTokensUsed.addAndGet(completion);
    // totalTokensUsed.addAndGet(prompt + completion);
    // }

    public long getTotalTokensUsed() {
        return totalTokensUsed.get();
    }

    public long getPromptTokensUsed() {
        return promptTokensUsed.get();
    }

    public long getCompletionTokensUsed() {
        return completionTokensUsed.get();
    }

    /** 返回按模型分桶的输入输出指标详情 */
    public Map<String, TokenPairView> getTokensByModel() {
        Map<String, TokenPairView> result = new LinkedHashMap<>();
        tokensByModel.forEach((k, v) -> {
            result.put(k, new TokenPairView(v.prompt.get(), v.completion.get()));
        });
        return result;
    }

    public void recordCall() {
        totalCalls.incrementAndGet();
    }

    public void recordCalls(long count) {
        if (count > 0)
            totalCalls.addAndGet(count);
    }

    public long getTotalCalls() {
        return totalCalls.get();
    }

    public String getChannelId() {
        return channelId;
    }

    public int getBaseWeight() {
        return baseWeight;
    }

    public void setBaseWeight(int baseWeight) {
        this.baseWeight = baseWeight;
    }

    public long getAverageModelLatencyMs() {
        return averageModelLatencyMs.get();
    }

    public void recordModelLatency(long durationMs) {
        if (durationMs <= 0)
            return;
        long currentAvg = averageModelLatencyMs.get();
        latencySampleCount.incrementAndGet();
        if (currentAvg == 0) {
            averageModelLatencyMs.set(durationMs);
        } else {
            long newAvg = (long) (currentAvg * 0.7 + durationMs * 0.3);
            averageModelLatencyMs.set(newAvg);
        }
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public double getRecentErrorCount() {
        return recentErrorCount.get();
    }

    public void recordError() {
        errorCount.incrementAndGet();
        recentErrorCount.incrementAndGet();
    }

    // 初始化时只记录 errorCount
    public void recordErrors(long count) {
        if (count > 0) {
            errorCount.addAndGet(count);
        }
    }

    public void decayErrors() {
        long current = recentErrorCount.get();
        if (current > 0) {
            recentErrorCount.compareAndSet(current, current / 2);
        }
    }

    public long getCurrentConcurrentCalls() {
        return currentConcurrentCalls.sum();
    }

    public void incrementConcurrent() {
        currentConcurrentCalls.increment();
    }

    public void decrementConcurrent() {
        currentConcurrentCalls.decrement();
    }

    /** 内部使用的可变计数对 */
    private static class TokenPair {
        final AtomicLong prompt = new AtomicLong(0);
        final AtomicLong completion = new AtomicLong(0);
    }

    /** 曝光给外部 DTO 或 JSON 的视图类 */
    @Data
    public static class TokenPairView {
        private final long p; // prompt
        private final long c; // completion
        private final long t; // total

        public TokenPairView(long p, long c) {
            this.p = p;
            this.c = c;
            this.t = p + c;
        }
    }
}
