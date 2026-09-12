package com.openrouter.metrics;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * 单个渠道的动态监控状态机，同时维护渠道级汇总与 (渠道, 模型) 级明细。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>延迟、失败率、TTFT 均按 (渠道, 模型) 分桶，避免同渠道下快慢模型互相污染；</li>
 *   <li>失败率是「近期失败比率」而非绝对计数：每发生一次调用乘衰减系数，
 *       并按 errorDecaySeconds 的时间半衰期惰性衰减，流量规模不影响其含义；</li>
 *   <li>TTFT 只有流式成功样本，非流式/首包前失败不产生样本；</li>
 *   <li>无样本时的读取语义由决策层处理（冷启动中性），采集层不返回 0 冒充有效值。</li>
 * </ul>
 * 所有写入均为 O(1)，读写之间的同步只发生在单个计数桶上。
 */
public class ModelMetrics {

    private final String channelId;

    /** 配置供应器：支持运行中热更路由参数而无需重建指标对象 */
    private final DoubleSupplier ewmaAlpha;
    private final LongSupplier errorDecaySeconds;
    private final DoubleSupplier errorDecayFactor;

    // =============== 渠道级汇总（服务 auto 模式与诊断展示） ===============
    private final LongAdder currentConcurrentCalls = new LongAdder();
    private final LongAdder totalCalls = new LongAdder();
    private final AtomicLong totalTokensUsed = new AtomicLong(0);
    private final AtomicLong promptTokensUsed = new AtomicLong(0);
    private final AtomicLong completionTokensUsed = new AtomicLong(0);

    /** 全量错误数：只由 DB 预热与实时错误累加，用于对账与展示 */
    private final AtomicLong errorCount = new AtomicLong(0);

    private final Health channelHealth;
    private final Latency channelLatency;

    // =============== (渠道, 模型) 级明细 ===============
    private final ConcurrentHashMap<String, ModelState> modelStates = new ConcurrentHashMap<>();

    public ModelMetrics(String channelId) {
        this(channelId, () -> 0.1, () -> 60L, () -> 0.5);
    }

    public ModelMetrics(String channelId, DoubleSupplier ewmaAlpha,
                        LongSupplier errorDecaySeconds, DoubleSupplier errorDecayFactor) {
        this.channelId = channelId;
        this.ewmaAlpha = ewmaAlpha;
        this.errorDecaySeconds = errorDecaySeconds;
        this.errorDecayFactor = errorDecayFactor;
        this.channelHealth = new Health(errorDecaySeconds, errorDecayFactor);
        this.channelLatency = new Latency();
    }

    // ==================== 写入路径 ====================

    /** 一次底层尝试开始：占用一路并发，并计入渠道级与 (渠道,模型) 级的尝试次数 */
    public void beginCall(String model) {
        currentConcurrentCalls.increment();
        totalCalls.increment();
        stateOf(model).calls.increment();
    }

    /** 一次底层尝试结束：释放并发 */
    public void endCall() {
        currentConcurrentCalls.decrement();
    }

    /** 非流式成功：记录完整耗时 */
    public void recordSuccess(String model, long durationMs) {
        channelHealth.recordSuccess();
        channelLatency.record(durationMs, alpha());
        stateOf(model).recordSuccess(durationMs, alpha());
    }

    /** 流式成功：记录完整耗时与首包延迟（ttft 可为 null，表示未观测到首包） */
    public void recordStreamSuccess(String model, long durationMs, Long ttftMs) {
        channelHealth.recordSuccess();
        channelLatency.record(durationMs, alpha());
        stateOf(model).recordStreamSuccess(durationMs, ttftMs, alpha());
    }

    /** 调用失败：按错误类型计入失败率（CLIENT 类错误不计入） */
    public void recordFailure(String model, Throwable error) {
        ErrorKind kind = ErrorKind.classify(error);
        errorCount.incrementAndGet();
        channelHealth.recordError(kind);
        stateOf(model).recordError(kind);
    }

    /** 原子级增加 Token (带模型名分账) */
    public void addTokens(String model, long prompt, long completion) {
        long total = prompt + completion;
        promptTokensUsed.addAndGet(prompt);
        completionTokensUsed.addAndGet(completion);
        totalTokensUsed.addAndGet(total);
        if (model != null && !model.isBlank()) {
            ModelState st = stateOf(model);
            st.promptTokens.addAndGet(prompt);
            st.completionTokens.addAndGet(completion);
            st.totalTokens.addAndGet(total);
        }
    }

    // ==================== 读取路径 ====================

    public String getChannelId() {
        return channelId;
    }

    public long getCurrentConcurrentCalls() {
        return currentConcurrentCalls.sum();
    }

    public long getTotalCalls() {
        return totalCalls.sum();
    }

    public long getTotalTokensUsed() {
        return totalTokensUsed.get();
    }

    public long getPromptTokensUsed() {
        return promptTokensUsed.get();
    }

    public long getCompletionTokensUsed() {
        return completionTokensUsed.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    /** 渠道级延迟（所有模型汇总），仅作诊断与 auto 模式参考 */
    public long getAverageModelLatencyMs() {
        return channelLatency.ewmaMs();
    }

    public long getChannelLatencySamples() {
        return channelLatency.samples();
    }

    /**
     * 近期失败率：model 为空/auto 时取渠道级汇总，否则取该模型的分桶。
     * 返回 [0,1]；无数据时为 0（无失败历史）。
     */
    public double getFailureRate(String model) {
        if (model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)) {
            ModelState st = modelStates.get(model);
            if (st != null) return st.health.rate();
        }
        return channelHealth.rate();
    }

    /**
     * 延迟视图：ewma 为 0 且 samples 为 0 表示无有效样本。
     * <p>
     * 指定模型时严格使用该模型的分桶：延迟是模型属性（不同模型解码速度差异巨大），
     * 缺样本时交由决策层走冷启动中性值，不用渠道级汇总冒充。
     */
    public LatencyView getLatency(String model) {
        if (model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)) {
            ModelState st = modelStates.get(model);
            return st == null ? LatencyView.empty() : st.latency.view();
        }
        return channelLatency.view();
    }

    /** 首包延迟视图；无流式样本时 samples 为 0 */
    public LatencyView getTtft(String model) {
        if (model == null || model.isBlank() || "auto".equalsIgnoreCase(model)) {
            return LatencyView.empty();
        }
        ModelState st = modelStates.get(model);
        return st == null ? LatencyView.empty() : st.ttft.view();
    }

    /** 返回按模型分桶的输入输出指标详情（兼容既有展示结构） */
    public Map<String, TokenPairView> getTokensByModel() {
        Map<String, TokenPairView> result = new LinkedHashMap<>();
        modelStates.forEach((k, v) ->
                result.put(k, new TokenPairView(v.promptTokens.get(), v.completionTokens.get())));
        return result;
    }

    /** 按模型分桶的完整指标视图，供管理页展示 */
    public List<ModelStateView> getModelViews() {
        List<ModelStateView> views = new ArrayList<>(modelStates.size());
        modelStates.forEach((name, st) -> views.add(new ModelStateView(
                name,
                st.calls.sum(),
                st.latency.ewmaMs(),
                st.latency.samples(),
                st.ttft.samples() == 0 ? null : st.ttft.ewmaMs(),
                st.ttft.samples(),
                round4(st.health.rate()),
                st.promptTokens.get(),
                st.completionTokens.get(),
                st.totalTokens.get())));
        views.sort((a, b) -> Long.compare(b.getCalls(), a.getCalls()));
        return views;
    }

    // ==================== 预热（启动时从 DB 恢复） ====================

    /** 恢复渠道级延迟：avgMs 为历史均值，samples 为参与统计的样本数 */
    public void warmupChannelLatency(long avgMs, long samples) {
        channelLatency.warmup(avgMs, samples);
    }

    /** 恢复 (渠道, 模型) 延迟 */
    public void warmupLatency(String model, long avgMs, long samples) {
        if (model != null && !model.isBlank()) {
            stateOf(model).latency.warmup(avgMs, samples);
        }
    }

    /** 恢复 (渠道, 模型) 首包延迟（仅流式样本） */
    public void warmupTtft(String model, long avgMs, long samples) {
        if (model != null && !model.isBlank()) {
            stateOf(model).ttft.warmup(avgMs, samples);
        }
    }

    /** 恢复全量调用计数（不进入近期失败率，保持「重启后近期状态清零」的语义） */
    public void recordCalls(long count) {
        if (count > 0) totalCalls.add(count);
    }

    /** 恢复 (渠道, 模型) 的尝试次数（与渠道级 totalCalls 同口径） */
    public void recordModelCalls(String model, long count) {
        if (count > 0 && model != null && !model.isBlank()) {
            stateOf(model).calls.add(count);
        }
    }

    /** 恢复全量错误计数（仅 errorCount，不影响失败率） */
    public void recordErrors(long count) {
        if (count > 0) errorCount.addAndGet(count);
    }

    // ==================== 内部实现 ====================

    private double alpha() {
        double a = ewmaAlpha.getAsDouble();
        return (a <= 0 || a > 1) ? 0.1 : a;
    }

    private ModelState stateOf(String model) {
        String key = (model == null || model.isBlank()) ? "unknown" : model;
        return modelStates.computeIfAbsent(key,
                k -> new ModelState(errorDecaySeconds, errorDecayFactor));
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 失败率桶：带时间半衰期的 EWMA，惰性衰减（无需定时任务） */
    static final class Health {
        private final LongSupplier decaySeconds;
        private final DoubleSupplier decayFactor;
        private double rate;
        private long lastUpdateMs;

        Health(LongSupplier decaySeconds, DoubleSupplier decayFactor) {
            this.decaySeconds = decaySeconds;
            this.decayFactor = decayFactor;
        }

        synchronized void recordSuccess() {
            rate = decayed() * factor();
            lastUpdateMs = System.currentTimeMillis();
        }

        synchronized void recordError(ErrorKind kind) {
            double weight = kind.healthWeight();
            double base = decayed() * factor();
            rate = weight <= 0 ? base : Math.min(1.0, base + weight * (1 - factor()));
            lastUpdateMs = System.currentTimeMillis();
        }

        synchronized double rate() {
            double current = decayed();
            lastUpdateMs = System.currentTimeMillis();
            return current;
        }

        /** 按距上次更新的时长做半衰期衰减，返回衰减后的值 */
        private double decayed() {
            if (lastUpdateMs == 0) return rate;
            long elapsed = System.currentTimeMillis() - lastUpdateMs;
            if (elapsed <= 0) return rate;
            long periodMs = Math.max(1, decaySeconds.getAsLong()) * 1000L;
            return rate * Math.pow(factor(), (double) elapsed / periodMs);
        }

        private double factor() {
            double f = decayFactor.getAsDouble();
            return (f <= 0 || f >= 1) ? 0.5 : f;
        }
    }

    /** 延迟/首包延迟桶：EMA + 样本数 */
    static final class Latency {
        private double ewma;
        private long samples;

        synchronized void record(long valueMs, double alpha) {
            if (valueMs <= 0) return;
            ewma = samples == 0 ? valueMs : alpha * valueMs + (1 - alpha) * ewma;
            samples++;
        }

        synchronized void warmup(long avgMs, long sampleCount) {
            if (avgMs <= 0) return;
            ewma = avgMs;
            samples = Math.max(sampleCount, 1);
        }

        synchronized long ewmaMs() {
            return Math.round(ewma);
        }

        synchronized long samples() {
            return samples;
        }

        synchronized LatencyView view() {
            return new LatencyView(Math.round(ewma), samples);
        }
    }

    /** 单个模型的全部指标 */
    static final class ModelState {
        final Latency latency;
        final Latency ttft;
        final Health health;
        final LongAdder calls = new LongAdder();
        final AtomicLong promptTokens = new AtomicLong(0);
        final AtomicLong completionTokens = new AtomicLong(0);
        final AtomicLong totalTokens = new AtomicLong(0);

        ModelState(LongSupplier decaySeconds, DoubleSupplier decayFactor) {
            this.latency = new Latency();
            this.ttft = new Latency();
            this.health = new Health(decaySeconds, decayFactor);
        }

        void recordSuccess(long durationMs, double alpha) {
            latency.record(durationMs, alpha);
            health.recordSuccess();
        }

        void recordStreamSuccess(long durationMs, Long ttftMs, double alpha) {
            latency.record(durationMs, alpha);
            if (ttftMs != null && ttftMs > 0) {
                ttft.record(ttftMs, alpha);
            }
            health.recordSuccess();
        }

        void recordError(ErrorKind kind) {
            health.recordError(kind);
        }
    }

    /** 延迟读数：ewmaMs 仅在 samples > 0 时有意义 */
    public record LatencyView(long ewmaMs, long samples) {
        public static LatencyView empty() {
            return new LatencyView(0, 0);
        }
    }

    /** 曝光给管理页的 (渠道, 模型) 级指标 */
    @Data
    public static class ModelStateView {
        private final String model;
        private final long calls;
        private final long latencyEwmaMs;
        private final long latencySamples;
        private final Long ttftEwmaMs;
        private final long ttftSamples;
        private final double failureRate;
        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;
    }

    /** 曝光给外部 DTO 或 JSON 的 Token 视图 */
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
