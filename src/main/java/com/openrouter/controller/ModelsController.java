package com.openrouter.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openrouter.config.RouterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * OpenAI 兼容的 /v1/models 接口实现。
 * 策略：并发请求每个可用 Channel 的上游 /v1/models 接口，
 * 拉取到的模型列表按照 application.yaml 里声明的 models 过滤，
 * 最后将各渠道的过滤结果合并，去重，按 OpenAI 格式输出。
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "*")
public class ModelsController {

    private final RouterProperties routerProperties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ModelsController(RouterProperties routerProperties,
                            WebClient routerWebClient,
                            ObjectMapper objectMapper) {
        this.routerProperties = routerProperties;
        this.webClient = routerWebClient;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /v1/models
     * 并发拉取所有已启用 Channel 的 /v1/models 上游，过滤后聚合返回。
     * Gemini 等非 OpenAI 协议的 Channel 不做查询（暂只支持 openai 类型）。
     */
    @GetMapping("/models")
    public Mono<Map<String, Object>> listModels() {
        List<RouterProperties.Channel> channels = routerProperties.getChannels();
        if (channels == null || channels.isEmpty()) {
            return Mono.just(buildResponse(Collections.emptyList()));
        }

        // 为每个 openai 类型已启用的 Channel 并发发起上游 /v1/models 请求
        List<Mono<List<Map<String, Object>>>> requests = channels.stream()
                .filter(ch -> ch.isEnabled() && "openai".equalsIgnoreCase(ch.getType()))
                .map(ch -> fetchModelsFromChannel(ch)
                        .doOnError(e -> log.warn("⚠️ 拉取 {} 模型列表失败: {}", ch.getId(), e.getMessage()))
                        .onErrorReturn(Collections.emptyList()))
                .toList();

        if (requests.isEmpty()) {
            return Mono.just(buildResponse(Collections.emptyList()));
        }

        return Flux.merge(requests)
                .collectList()
                .map(results -> {
                    // 将所有 channel 的结果合并，按 model id 去重
                    Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
                    for (List<Map<String, Object>> list : results) {
                        for (Map<String, Object> model : list) {
                            String id = (String) model.get("id");
                            if (id != null) deduped.put(id, model);
                        }
                    }
                    return buildResponse(new ArrayList<>(deduped.values()));
                });
    }

    /**
     * 向单个 Channel 的上游发起 GET /v1/models 请求，
     * 并按该 channel 的 models 白名单进行过滤。
     */
    private Mono<List<Map<String, Object>>> fetchModelsFromChannel(RouterProperties.Channel channel) {
        Set<String> allowedModels = new HashSet<>(
                channel.getModels() != null ? channel.getModels() : Collections.emptyList()
        );

        return webClient.get()
                .uri(channel.getBaseUrl() + "/v1/models")
                .header("Authorization", "Bearer " + channel.getApiKey())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    List<Map<String, Object>> filtered = new ArrayList<>();
                    JsonNode data = json.get("data");
                    if (data == null || !data.isArray()) return filtered;

                    for (JsonNode modelNode : data) {
                        String modelId = modelNode.path("id").asText(null);
                        if (modelId == null) continue;
                        // 只保留 yaml 里声明的模型
                        if (allowedModels.contains(modelId)) {
                            // 构建标准化的 model 对象（兼容 OpenAI 格式）
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", modelId);
                            m.put("object", "model");
                            m.put("created", modelNode.path("created").asLong(System.currentTimeMillis() / 1000));
                            m.put("owned_by", modelNode.path("owned_by").asText(channel.getId()));
                            filtered.add(m);
                            log.debug("✅ 收录模型: {} (来自渠道: {})", modelId, channel.getId());
                        }
                    }
                    log.info("📦 渠道 {} 拉取并过滤后得到 {} 个模型", channel.getId(), filtered.size());
                    return filtered;
                });
    }

    private Map<String, Object> buildResponse(List<Map<String, Object>> models) {
        // 注入 "auto" 虚拟模型，永远排在第一位
        Map<String, Object> autoModel = new LinkedHashMap<>();
        autoModel.put("id", "auto");
        autoModel.put("object", "model");
        autoModel.put("created", System.currentTimeMillis() / 1000);
        autoModel.put("owned_by", "openrouter");

        List<Map<String, Object>> allModels = new ArrayList<>();
        allModels.add(autoModel);
        allModels.addAll(models);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("object", "list");
        resp.put("data", allModels);
        return resp;
    }
}
