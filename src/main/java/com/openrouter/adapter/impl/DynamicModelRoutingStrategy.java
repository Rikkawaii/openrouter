package com.openrouter.adapter.impl;

import com.openrouter.adapter.ModelRoutingStrategy;
import com.openrouter.config.ModelCapabilitiesProperties;
import com.openrouter.config.RouterProperties;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.metrics.ModelMetrics;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.service.RequestCapabilityDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智慧路由打分工厂核心枢纽！
 */
@Slf4j
@Primary
@Service
public class DynamicModelRoutingStrategy implements ModelRoutingStrategy {

    private final MetricsRegistry metricsRegistry;
    private final ModelCapabilitiesProperties capabilitiesProperties;

    // ===== 动态分平衡系数 ======
    private static final double HEALTH_PENALTY_WEIGHT = 10.0;
    private static final double LATENCY_WEIGHT = 0.05;
    private static final double CONSECUTIVE_CALL_PENALTY = 5.0;

    public DynamicModelRoutingStrategy(MetricsRegistry metricsRegistry,
                                       ModelCapabilitiesProperties capabilitiesProperties) {
        this.metricsRegistry = metricsRegistry;
        this.capabilitiesProperties = capabilitiesProperties;
    }

    @Override
    public RouterProperties.Channel selectChannel(ChatCompletionRequest request, List<RouterProperties.Channel> availableChannels) {
        String targetModel = request.getModel();
        if (!StringUtils.hasText(targetModel)) {
            throw new IllegalArgumentException("Model name is required for routing.");
        }

        // 挑选出启用状态的活频道 (手动热拔插控制位起效)
        List<RouterProperties.Channel> activeChannels = availableChannels.stream()
                .filter(RouterProperties.Channel::isEnabled)
                .collect(Collectors.toList());

        if (activeChannels.isEmpty()) {
            return null;
        }

        // 【特定模型路由模式】：请求指定了具体模型，需从支持该模型的节点中进行优选
        if (!"auto".equalsIgnoreCase(targetModel)) {
            activeChannels = activeChannels.stream()
                    .filter(c -> c.getModels() != null && c.getModels().contains(targetModel))
                    .collect(Collectors.toList());
            if (activeChannels.isEmpty()) {
                log.warn("未找到任何支持模型 {} 且已启用的可用渠道！", targetModel);
                return null;
            }
        }

        // 【能力边界过滤】：根据请求实际所需能力，过滤掉不满足要求的渠道
        RequestCapabilityDetector.RequiredCapabilities requiredCaps = new RequestCapabilityDetector().detect(request);
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

        // ====== 【智能动态路由 Auto 模式核心区】 ======
        // 针对 Channels（同一个底层协议比如 openai 可以挂钩 N 个 channel）逐一算分对决
        RouterProperties.Channel bestChannel = activeChannels.stream()
                .max(Comparator.comparingDouble(this::calculateScore))
                .orElse(null);
                
        if (bestChannel != null) {
            log.info("🎯 [Auto Routing] 最终选中了得分最高的服务渠道节点: {}", bestChannel.getId());
        }
        
        return bestChannel;
    }

    public double calculateScore(RouterProperties.Channel channel) {
        ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());

        // 始终读取 yaml 配置当前的固定分（如果做成控制台热更，能立马拿得到最新配置）
        int baseWeight = channel.getBaseWeight();
        
        // 【路由惩罚】使用近期错误惩罚值（随时间衰减, 不影响历史账本）
        double recentErrorPenalty = metrics.getRecentErrorScore();
        long avgLatency = metrics.getAverageLatencyMs();
        long concurrentCalls = metrics.getCurrentConcurrentCalls();

        // 黄金打分公式
        double finalScore = baseWeight 
                - (recentErrorPenalty * HEALTH_PENALTY_WEIGHT) 
                - (avgLatency * LATENCY_WEIGHT) 
                - (concurrentCalls * CONSECUTIVE_CALL_PENALTY);

        log.debug("📊 打分详情 - 渠道: {}, 基础分: {}, 近期错误惩罚: {}, TTFT扣减: {}ms, 高流阻力: {}, -> 综合总得分为: {}", 
                channel.getId(), baseWeight, recentErrorPenalty, avgLatency, concurrentCalls, finalScore);
        
        return finalScore;
    }

    /**
     * 判断某个 Channel 是否支持 Vision 能力。
     * - 如果请求的是具体模型 (非 auto)，检查该模型的能力注册。
     * - 如果是 auto 模式，检查该 Channel 的 models 列表中是否有至少一个 vision 模型。
     */
    private boolean channelSupportsVision(RouterProperties.Channel channel, String targetModel) {
        if (channel.getModels() == null || channel.getModels().isEmpty()) return false;

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

