package com.openrouter.adapter.impl;

import com.openrouter.adapter.ModelRoutingStrategy;
import com.openrouter.config.ModelCapabilitiesProperties;
import com.openrouter.config.ChannelConfig;
import com.openrouter.config.RouterProperties;
import com.openrouter.config.RoutingConfig;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.metrics.ModelMetrics;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.service.RequestCapabilityDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 智慧路由打分工厂核心枢纽。
 * <p>
 * 打分公式（各子分归一化到 [0,1]，结果有界于 [0, baseWeight]）：
 * <pre>
 * score = baseWeight × (1 - healthWeight×H - latencyWeight×L - loadWeight×C)
 * </pre>
 * 参数来自 channels.json 的 settings.routing 段（管理页可热更）。
 * 指定模型时使用 (渠道, 模型) 级指标，auto 模式使用渠道级汇总。
 */
@Slf4j
@Primary
@Service
public class DynamicModelRoutingStrategy implements ModelRoutingStrategy {

    private final MetricsRegistry metricsRegistry;
    private final ModelCapabilitiesProperties capabilitiesProperties;
    private final RequestCapabilityDetector requestCapabilityDetector;
    private final RouterProperties routerProperties;

    public DynamicModelRoutingStrategy(MetricsRegistry metricsRegistry,
            ModelCapabilitiesProperties capabilitiesProperties,
            RequestCapabilityDetector requestCapabilityDetector,
            RouterProperties routerProperties) {
        this.metricsRegistry = metricsRegistry;
        this.capabilitiesProperties = capabilitiesProperties;
        this.requestCapabilityDetector = requestCapabilityDetector;
        this.routerProperties = routerProperties;
    }

    @Override
    public ChannelConfig selectChannel(ChatCompletionRequest request,
            List<ChannelConfig> availableChannels) {
        String targetModel = request.getModel();
        if (!StringUtils.hasText(targetModel)) {
            throw new IllegalArgumentException("Model name is required for routing.");
        }

        // 挑选出启用状态的活频道 (手动热拔插控制位起效)
        List<ChannelConfig> activeChannels = availableChannels.stream()
                .filter(ChannelConfig::isEnabled)
                .collect(Collectors.toList());

        if (activeChannels.isEmpty()) {
            return null;
        }

        // 【特定模型路由模式】：请求指定了具体模型，需从支持该模型的节点中进行优选
        boolean autoMode = "auto".equalsIgnoreCase(targetModel);
        if (!autoMode) {
            activeChannels = activeChannels.stream()
                    .filter(c -> c.getModels() != null && c.getModels().contains(targetModel))
                    .collect(Collectors.toList());
            if (activeChannels.isEmpty()) {
                log.warn("未找到任何支持模型 {} 且已启用的可用渠道！", targetModel);
                return null;
            }
        }

        // 【能力边界过滤】：根据请求实际所需能力，过滤掉不满足要求的渠道
        RequestCapabilityDetector.RequiredCapabilities requiredCaps = requestCapabilityDetector.detect(request);
        if (requiredCaps.isVision()) {
            log.info("🖼️ 检测到多模态请求（含图片/文件），自动过滤仅支持文本的渠道...");
            activeChannels = activeChannels.stream()
                    .filter(c -> channelSupportsVision(c, targetModel))
                    .collect(Collectors.toList());
            if (activeChannels.isEmpty()) {
                log.warn("❌ 没有任何渠道具备视觉识别能力，无法处理该多模态请求！");
                return null;
            }
        }

        // ====== 【智能动态路由核心区】 ======
        // 指定模型时按该模型的 (渠道, 模型) 指标打分；auto 时退化为渠道级汇总
        String scoringModel = autoMode ? null : targetModel;
        ChannelConfig bestChannel = pickBest(activeChannels, scoringModel);

        if (bestChannel != null) {
            log.info("🎯 [动态路由] 选中渠道 {} (得分 {}, 模型 {})",
                    bestChannel.getId(), String.format("%.1f", calculateScore(bestChannel, scoringModel)),
                    scoringModel != null ? scoringModel : "auto");
        }

        return bestChannel;
    }

    /** 按得分降序挑选；启用探索时以配置概率在前两名中随机，避免流量长期锁定 */
    private ChannelConfig pickBest(List<ChannelConfig> candidates, String model) {
        List<ChannelConfig> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((ChannelConfig c) -> calculateScore(c, model)).reversed());
        if (sorted.isEmpty()) {
            return null;
        }
        double rate = routing().getExplorationRate();
        if (rate > 0 && sorted.size() > 1 && ThreadLocalRandom.current().nextDouble() < rate) {
            return ThreadLocalRandom.current().nextBoolean() ? sorted.get(0) : sorted.get(1);
        }
        return sorted.get(0);
    }

    /** 渠道级打分（auto 模式 / 仪表盘展示口径） */
    public double calculateScore(ChannelConfig channel) {
        return calculateScore(channel, null);
    }

    /**
     * 纯函数打分：给定渠道与其指标快照输出得分，无 IO、无副作用，便于单元测试。
     *
     * @param model 指定模型时使用该模型的分桶指标；null 或 auto 使用渠道级汇总
     */
    public double calculateScore(ChannelConfig channel, String model) {
        RoutingConfig cfg = routing();
        ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());

        // H：近期失败率，上限 1
        double health = clamp01(metrics.getFailureRate(model));

        // L：延迟子分；样本不足时使用冷启动中性值（不为 0，避免"未知即最优"）
        ModelMetrics.LatencyView latency = metrics.getLatency(model);
        double latencyScore = latency.samples() >= cfg.getMinLatencySamples()
                ? saturate(latency.ewmaMs(), cfg.getLatencyReferenceMs())
                : clamp01(cfg.getColdStartLatencyRatio());

        // C：负载子分
        double load = saturate(metrics.getCurrentConcurrentCalls(), cfg.getConcurrencyCapacity());

        double raw = channel.getBaseWeight() * (1
                - cfg.getHealthWeight() * health
                - cfg.getLatencyWeight() * latencyScore
                - cfg.getLoadWeight() * load);
        double score = Math.max(0, raw);

        log.debug("📊 打分详情 - 渠道: {}, 模型: {}, 基础分: {}, 健康度: {} (子分 {}), 延迟: {}ms/{}样本 (子分 {}), 并发: {} (子分 {}), 得分: {}",
                channel.getId(), model != null ? model : "auto", channel.getBaseWeight(),
                metrics.getFailureRate(model), String.format("%.3f", health),
                latency.ewmaMs(), latency.samples(), String.format("%.3f", latencyScore),
                metrics.getCurrentConcurrentCalls(), String.format("%.3f", load),
                String.format("%.2f", score));

        return score;
    }

    /** 饱和函数：单调递增、值域 [0,1)，避免绝对量纲与硬截断 */
    private static double saturate(long value, long reference) {
        if (value <= 0 || reference <= 0) return 0;
        return (double) value / (value + reference);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private RoutingConfig routing() {
        RoutingConfig cfg = routerProperties.getRouting();
        return cfg != null ? cfg : new RoutingConfig();
    }

    /**
     * 判断某个 Channel 是否支持 Vision 能力。
     * - 如果请求的是具体模型 (非 auto)，检查该模型的能力注册。
     * - 如果是 auto 模式，检查该 Channel 的 models 列表中是否有至少一个 vision 模型。
     */
    private boolean channelSupportsVision(ChannelConfig channel, String targetModel) {
        if (channel.getModels() == null || channel.getModels().isEmpty())
            return false;

        if ("auto".equalsIgnoreCase(targetModel)) {
            // auto 模式：channel 内任意一个模型具备 vision 即可通过
            return channel.getModels().stream()
                    .anyMatch(m -> capabilitiesProperties.getCapabilityForModel(m).isVision());
        } else {
            // 具体模型模式：直接查询该模型的能力
            return capabilitiesProperties.getCapabilityForModel(targetModel).isVision();
        }
    }
}
