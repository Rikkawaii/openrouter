package com.openrouter.adapter.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openrouter.adapter.LlmClientAdapter;
import com.openrouter.config.RouterProperties;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.metrics.ModelMetrics;
import com.openrouter.model.ChatCompletionMessage;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class GeminiLlmAdapter implements LlmClientAdapter {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MetricsRegistry metricsRegistry;

    public GeminiLlmAdapter(WebClient routerWebClient, ObjectMapper objectMapper, MetricsRegistry metricsRegistry) {
        this.webClient = routerWebClient;
        this.objectMapper = objectMapper;
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    public String getProtocolType() {
        return "gemini";
    }

    @Override
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request, RouterProperties.Channel channel) {
        return Mono.error(new UnsupportedOperationException("Sync chat for Gemini is not implemented yet in this Router."));
    }

    @Override
    public Flux<String> streamChat(ChatCompletionRequest request, RouterProperties.Channel channel) {
        log.info("gemini stream");
        // 模型代号已经在 Service 层基于 models 列表装配完毕
        String model = request.getModel();
        
        String url = channel.getBaseUrl() + "/v1beta/models/" + model + ":streamGenerateContent?alt=sse&key=" + channel.getApiKey();

        String geminiJsonBody = convertToGeminiRequest(request);
        ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(geminiJsonBody)
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(data -> {
                    try {
                        return convertToOpenAiChunk(data, model);
                    } catch (Exception e) {
                        log.error("Failed to parse Gemini chunk, data: {}", data, e);
                        return null;
                    }
                })
                .concatWith(Flux.just("[DONE]"))
                .doOnSubscribe(s -> {
                    metrics.incrementConcurrent();
                    metrics.recordCall();
                })
                .transform(flux -> {
                    long startTime = System.currentTimeMillis();
                    AtomicBoolean first = new AtomicBoolean(true);
                    return flux.doOnNext(item -> {
                        if (first.compareAndSet(true, false)) {
                            metrics.recordLatency(System.currentTimeMillis() - startTime);
                        }
                        // 拦截 Gemini 的原生算费账单
                        if (item != null && item.contains("\"usageMetadata\"")) {
                            try {
                                // Gemini 的结构是 promptTokenCount 和 candidatesTokenCount
                                java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("\"promptTokenCount\"\\s*:\\s*(\\d+)").matcher(item);
                                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\"candidatesTokenCount\"\\s*:\\s*(\\d+)").matcher(item);
                                if (m1.find() && m2.find()) {
                                    metrics.addTokens(model, 
                                        Long.parseLong(m1.group(1)), 
                                        Long.parseLong(m2.group(1)));
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                })
                .doOnError(e -> metrics.recordError())
                .doFinally(sig -> metrics.decrementConcurrent());
    }

    private String convertToGeminiRequest(ChatCompletionRequest request) {
        try {
            List<Map<String, Object>> contents = new ArrayList<>();
            for (ChatCompletionMessage msg : request.getMessages()) {
                Map<String, Object> contentMap = new HashMap<>();
                String role = "user".equalsIgnoreCase(msg.getRole()) ? "user" : "model";
                contentMap.put("role", role);

                Object rawContent = msg.getContent();
                List<Map<String, Object>> parts = new ArrayList<>();

                if (rawContent instanceof String) {
                    // 纯文本消息 - 最常见的历史格式
                    parts.add(Collections.singletonMap("text", rawContent));
                } else if (rawContent instanceof List) {
                    // 多模态内容数组 - 逐个条目翻译成 Gemini 的 parts 格式
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> contentParts = (List<Map<String, Object>>) rawContent;
                    for (Map<String, Object> part : contentParts) {
                        String type = (String) part.get("type");
                        if ("text".equals(type)) {
                            parts.add(Collections.singletonMap("text", part.get("text")));
                        } else if ("image_url".equals(type)) {
                            // 翻译 OpenAI image_url -> Gemini inlineData 或 fileData
                            @SuppressWarnings("unchecked")
                            Map<String, String> imageUrlObj = (Map<String, String>) part.get("image_url");
                            String url = imageUrlObj != null ? imageUrlObj.get("url") : null;
                            if (url != null && url.startsWith("data:")) {
                                // Base64 内联图片: data:image/jpeg;base64,xxxxxxx
                                int commaIdx = url.indexOf(',');
                                String mimeAndEncoding = url.substring(5, commaIdx); // image/jpeg;base64
                                String mimeType = mimeAndEncoding.split(";")[0];     // image/jpeg
                                String base64Data = url.substring(commaIdx + 1);
                                Map<String, Object> inlineData = new HashMap<>();
                                inlineData.put("mimeType", mimeType);
                                inlineData.put("data", base64Data);
                                parts.add(Collections.singletonMap("inlineData", inlineData));
                            } else if (url != null) {
                                // 外部 URL 图片: 对于 Gemini，使用 fileData 格式
                                Map<String, Object> fileData = new HashMap<>();
                                fileData.put("mimeType", "image/jpeg"); // Gemini 要求提供 mimeType
                                fileData.put("fileUri", url);
                                parts.add(Collections.singletonMap("fileData", fileData));
                            }
                        }
                        // file 类型暂不在 Gemini 端翻译（Gemini 文件 API 需要单独上传），跳过
                    }
                }

                contentMap.put("parts", parts);
                contents.add(contentMap);
            }

            Map<String, Object> finalBody = new HashMap<>();
            finalBody.put("contents", contents);
            if (request.getTemperature() != null) {
                finalBody.put("generationConfig", Collections.singletonMap("temperature", request.getTemperature()));
            }
            return objectMapper.writeValueAsString(finalBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert to Gemini failed", e);
        }
    }

    private String convertToOpenAiChunk(String geminiData, String model) throws Exception {
        JsonNode root = objectMapper.readTree(geminiData);
        JsonNode parts = root.at("/candidates/0/content/parts/0/text");
        String text = parts.isMissingNode() ? "" : parts.asText();

        if (text.isEmpty()) {
            return null;
        }

        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .index(0)
                .delta(ChatCompletionMessage.builder().content(text).build())
                .build();

        ChatCompletionResponse chunk = ChatCompletionResponse.builder()
                .id("chatcmpl-" + UUID.randomUUID())
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(Collections.singletonList(choice))
                .build();

        return objectMapper.writeValueAsString(chunk);
    }
}
