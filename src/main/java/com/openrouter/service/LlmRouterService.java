package com.openrouter.service;

import com.openrouter.adapter.LlmClientAdapter;
import com.openrouter.adapter.ModelRoutingStrategy;
import com.openrouter.config.ModelCapabilitiesProperties;
import com.openrouter.config.RouterProperties;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmRouterService {

    // K-V 型字典: 协议 -> 适配器 (例如 "openai" -> OpenAiLlmAdapter)
    private final Map<String, LlmClientAdapter> protocolAdapters;
    private final ModelRoutingStrategy routingStrategy;
    private final RouterProperties routerProperties;
    private final RequestCapabilityDetector capabilityDetector;
    private final ModelCapabilitiesProperties capabilitiesProperties;

    public LlmRouterService(List<LlmClientAdapter> adapters,
                            ModelRoutingStrategy routingStrategy,
                            RouterProperties routerProperties,
                            RequestCapabilityDetector capabilityDetector,
                            ModelCapabilitiesProperties capabilitiesProperties) {
        this.protocolAdapters = adapters.stream()
                .collect(Collectors.toMap(LlmClientAdapter::getProtocolType, a -> a));
        this.routingStrategy = routingStrategy;
        this.routerProperties = routerProperties;
        this.capabilityDetector = capabilityDetector;
        this.capabilitiesProperties = capabilitiesProperties;
    }

    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request) {
        return chatWithFallback(request, new HashSet<>());
    }

    public Flux<String> streamChat(ChatCompletionRequest request) {
        return streamChatWithFallback(request, new HashSet<>());
    }

    // ==================== 带自动故障转移的核心执行方法 ====================

    /**
     * 非流式请求：失败时自动排除当前节点，递归重试下一个最优节点。
     */
    private Mono<ChatCompletionResponse> chatWithFallback(ChatCompletionRequest request, Set<String> excludedIds) {
        RouterProperties.Channel channel = selectBestChannel(request, excludedIds);
        if (channel == null) {
            String msg = excludedIds.isEmpty()
                    ? "No available channel for model: " + request.getModel()
                    : "All channels exhausted after " + excludedIds.size() + " failures. Giving up.";
            return Mono.error(new RuntimeException(msg));
        }

        // auto 模式：智能选一个满足请求能力要求的模型，而不是盲目取 index[0]
        String originalModel = request.getModel();
        if ("auto".equalsIgnoreCase(originalModel) && channel.getModels() != null && !channel.getModels().isEmpty()) {
            String selectedModel = selectCompatibleModel(request, channel);
            request.setModel(selectedModel);
            log.info("🤖 auto 模式选定具体模型: {} (来自渠道: {})", selectedModel, channel.getId());
        }

        log.info("🔀 [非流式] 正在尝试渠道: {} (已排除: {})", channel.getId(), excludedIds);
        LlmClientAdapter adapter = getAdapterForChannel(channel);
        final String channelId = channel.getId();

        return adapter.chat(request, channel)
                .onErrorResume(e -> {
                    log.warn("⚠️ 渠道 {} 调用失败 ({})，自动故障转移...", channelId, e.getMessage());
                    // 把原始 model 还原，以便下一个 channel 做 auto 注入时能重新选模型
                    request.setModel(originalModel);
                    Set<String> newExcluded = new HashSet<>(excludedIds);
                    newExcluded.add(channelId);
                    return chatWithFallback(request, newExcluded);
                });
    }

    /**
     * 流式请求：失败时自动排除当前节点，递归重试下一个最优节点。
     * 注意：由于 SSE 流式的特殊性，只有在连接建立前（header/connect 阶段）的错误才能被故障转移。
     * 流式传输开始后，中途断流只能在客户端处理。
     */
    private Flux<String> streamChatWithFallback(ChatCompletionRequest request, Set<String> excludedIds) {
        RouterProperties.Channel channel = selectBestChannel(request, excludedIds);
        if (channel == null) {
            String msg = excludedIds.isEmpty()
                    ? "No available channel for model: " + request.getModel()
                    : "All channels exhausted after " + excludedIds.size() + " failures. Giving up.";
            return Flux.error(new RuntimeException(msg));
        }

        String originalModel = request.getModel();
        if ("auto".equalsIgnoreCase(originalModel) && channel.getModels() != null && !channel.getModels().isEmpty()) {
            String selectedModel = selectCompatibleModel(request, channel);
            request.setModel(selectedModel);
            log.info("🤖 auto 流式模式选定具体模型: {} (来自渠道: {})", selectedModel, channel.getId());
        }

        log.info("🔀 [流式] 正在尝试渠道: {} (已排除: {})", channel.getId(), excludedIds);
        LlmClientAdapter adapter = getAdapterForChannel(channel);
        final String channelId = channel.getId();

        return adapter.streamChat(request, channel)
                .onErrorResume(e -> {
                    log.warn("⚠️ 流式渠道 {} 调用失败 ({})，自动故障转移...", channelId, e.getMessage());
                    request.setModel(originalModel);
                    Set<String> newExcluded = new HashSet<>(excludedIds);
                    newExcluded.add(channelId);
                    return streamChatWithFallback(request, newExcluded);
                });
    }

    // ==================== 辅助方法 ====================

    /**
     * 从所有已启用 Channel 中排除指定节点后选出得分最高的节点。
     */
    private RouterProperties.Channel selectBestChannel(ChatCompletionRequest request, Set<String> excludedIds) {
        List<RouterProperties.Channel> channels = routerProperties.getChannels();
        if (channels == null || channels.isEmpty()) {
            return null;
        }
        // 排除已经失败过的节点后再进行路由打分
        List<RouterProperties.Channel> candidates = channels.stream()
                .filter(c -> !excludedIds.contains(c.getId()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return null;
        return routingStrategy.selectChannel(request, candidates);
    }

    private LlmClientAdapter getAdapterForChannel(RouterProperties.Channel channel) {
        LlmClientAdapter adapter = protocolAdapters.get(channel.getType());
        if (adapter == null) {
            throw new RuntimeException("Protocol adapter translator [" + channel.getType() + "] not found for channel: " + channel.getId());
        }
        return adapter;
    }

    /**
     * 在 auto 模式下，从 channel 的 models 列表中选出第一个满足请求能力需求的模型。
     * 如果没有能力匹配的（理论上不会发生，因为路由过滤已保证 channel 通过能力检查），
     * 则降级返回第一个模型作为兜底。
     */
    private String selectCompatibleModel(ChatCompletionRequest request, RouterProperties.Channel channel) {
        RequestCapabilityDetector.RequiredCapabilities caps = capabilityDetector.detect(request);
        List<String> models = channel.getModels();

        if (caps.isVision()) {
            // 需要视觉：在 models 列表中找第一个具备 vision 能力的
            return models.stream()
                    .filter(m -> capabilitiesProperties.getCapabilityForModel(m).isVision())
                    .findFirst()
                    .orElseGet(() -> {
                        log.warn("⚠️ Channel {} 没有声明 vision 能力的模型，降级使用 index[0]: {}",
                                channel.getId(), models.get(0));
                        return models.get(0);
                    });
        }

        // 纯文本请求：直接用 index[0]（性价比最高的默认首选）
        return models.get(0);
    }
}
