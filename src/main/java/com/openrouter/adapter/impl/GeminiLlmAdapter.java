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
import com.openrouter.trace.RequestTraceContext;
import com.openrouter.service.UsageDatabaseService;
import com.openrouter.trace.TraceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GeminiLlmAdapter implements LlmClientAdapter {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MetricsRegistry metricsRegistry;
    private final TraceLogger traceLogger;
    private final UsageDatabaseService usageDatabaseService;

    private static final String geminiFunctionThoughtSignature = "skip_thought_signature_validator";

    private static final Pattern PROMPT_PATTERN = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_PATTERN = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");

    public GeminiLlmAdapter(WebClient routerWebClient, ObjectMapper objectMapper,
                            MetricsRegistry metricsRegistry, TraceLogger traceLogger, UsageDatabaseService usageDatabaseService) {
        this.webClient = routerWebClient;
        this.objectMapper = objectMapper;
        this.metricsRegistry = metricsRegistry;
        this.traceLogger = traceLogger;
        this.usageDatabaseService = usageDatabaseService;
    }

    @Override
    public String getProtocolType() {
        return "gemini";
    }

    @Override
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request, RouterProperties.Channel channel) {
        return Mono.error(
                new UnsupportedOperationException("Sync chat for Gemini is not implemented yet in this Router."));
    }

    @Override
    public Flux<String> streamChat(ChatCompletionRequest request, RouterProperties.Channel channel) {
        String model = request.getModel();
        String url = channel.getBaseUrl() + "/v1beta/models/" + model + ":streamGenerateContent?alt=sse";
        String geminiJsonBody = convertToGeminiRequest(request);
        ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
        RequestTraceContext ctx = request.getTraceContext();

        // 🧊 提前冻结快照，防止重试逻辑修改 request 后读到错误值
        final String targetModel = model;
        final String targetChannelId = channel.getId();
        final long startTime = System.currentTimeMillis();

        return webClient.post()
                .uri(url)
                .header("x-goog-api-key", channel.getApiKey())
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
                    // 🧊 最早时机注入：在任何 doFinally 触发之前，model/channelId 已就绪
                    if (ctx != null) {
                        ctx.setModel(targetModel);
                        ctx.setChannelId(targetChannelId);
                    }
                })
                .transform(flux -> {
                    AtomicBoolean first = new AtomicBoolean(true);
                    AtomicLong lastP = new AtomicLong(0);
                    AtomicLong lastC = new AtomicLong(0);

                    return flux.doOnNext(item -> {
                        if (first.compareAndSet(true, false)) {
                            if (ctx != null)
                                traceLogger.log(ctx, "FIRST_TOKEN", "success", "");
                        }

                        if (item != null && item.contains("\"usage\":{")) {
                            try {
                                long curP = queryToken(item, PROMPT_PATTERN);
                                long curC = queryToken(item, COMPLETION_PATTERN);
                                long deltaP = Math.max(0, curP - lastP.get());
                                long deltaC = Math.max(0, curC - lastC.get());
                                if (deltaP > 0 || deltaC > 0) {
                                    metrics.addTokens(targetModel, deltaP, deltaC);
                                    lastP.set(curP);
                                    lastC.set(curC);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    })
                            .doFinally(sig -> {
                                long callDuration = System.currentTimeMillis() - startTime;
                                boolean success = (sig == SignalType.ON_COMPLETE);
                                // model_call_log：每次底层尝试都记录
                                usageDatabaseService.saveCallLogAsync(ctx, targetChannelId, targetModel, callDuration, success, sig == SignalType.ON_ERROR ? "Stream error" : null);

                                if (success && ctx != null) {
                                    // ✅ 此处是 tokens 数据就绪的最早时机
                                    ctx.setPromptTokens(lastP.get());
                                    ctx.setCompletionTokens(lastC.get());
                                    ctx.setFullResponseJson("{\"usage\":{\"prompt_tokens\":" + lastP.get() + ",\"completion_tokens\":" + lastC.get() + "}}");
                                    metrics.recordModelLatency(callDuration);
                                    traceLogger.log(ctx, "FULL_RESPONSE", "success",
                                            "响应完整接收,Token消耗:p=" + lastP.get() + ", c=" + lastC.get());
                                    // ✅ request_log：在 tokens 确认后立即落库（此时 ctx 完整）
                                    usageDatabaseService.saveRequestLogAsync(ctx, true, null);
                                }
                            });
                })
                .doOnError(e -> metrics.recordError())
                .doFinally(sig -> metrics.decrementConcurrent());
    }

    private String convertToGeminiRequest(ChatCompletionRequest request) {
        try {
            Map<String, Object> finalBody = new LinkedHashMap<>();
            List<ChatCompletionMessage> messages = request.getMessages();

            if (messages == null || messages.isEmpty()) {
                finalBody.put("contents", Collections.emptyList());
                return objectMapper.writeValueAsString(finalBody);
            }

            Map<String, String> toolCallIdToName = new HashMap<>();
            for (ChatCompletionMessage msg : messages) {
                if ("assistant".equalsIgnoreCase(msg.getRole()) && msg.getToolCalls() != null) {
                    for (ChatCompletionMessage.ToolCall tc : msg.getToolCalls()) {
                        if (tc.getId() != null && tc.getFunction() != null && tc.getFunction().getName() != null) {
                            toolCallIdToName.put(tc.getId(), tc.getFunction().getName());
                        }
                    }
                }
            }
            List<Map<String, Object>> contents = new ArrayList<>();
            List<Map<String, Object>> systemParts = new ArrayList<>();

            for (ChatCompletionMessage msg : messages) {
                String role = msg.getRole();
                boolean isSystem = "system".equalsIgnoreCase(role) || "developer".equalsIgnoreCase(role);

                if (isSystem && messages.size() > 1) {
                    addParts(systemParts, msg, null, toolCallIdToName);
                    continue;
                }

                Map<String, Object> contentMap = new LinkedHashMap<>();
                String geminiRole;
                if ("tool".equalsIgnoreCase(role)) {
                    geminiRole = "user";
                } else if ("assistant".equalsIgnoreCase(role)) {
                    geminiRole = "model";
                } else {
                    geminiRole = "user";
                }
                contentMap.put("role", geminiRole);

                List<Map<String, Object>> parts = new ArrayList<>();
                addParts(parts, msg, role, toolCallIdToName);

                contentMap.put("parts", parts);
                contents.add(contentMap);
            }

            if (!systemParts.isEmpty()) {
                Map<String, Object> systemInstruction = new LinkedHashMap<>();
                systemInstruction.put("parts", systemParts);
                finalBody.put("systemInstruction", systemInstruction);
            }

            finalBody.put("contents", contents);

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            if (request.getTemperature() != null)
                generationConfig.put("temperature", request.getTemperature());
            if (request.getTopP() != null)
                generationConfig.put("topP", request.getTopP());
            if (request.getTopK() != null)
                generationConfig.put("topK", request.getTopK());
            if (request.getMaxTokens() != null)
                generationConfig.put("maxOutputTokens", request.getMaxTokens());

            if (request.getN() != null && request.getN() > 1) {
                generationConfig.put("candidateCount", request.getN());
            }

            if (request.getReasoningEffort() != null) {
                Map<String, Object> thinkingConfig = new HashMap<>();
                String effort = request.getReasoningEffort().toLowerCase();
                if ("auto".equals(effort)) {
                    thinkingConfig.put("thinkingBudget", -1);
                } else {
                    thinkingConfig.put("thinkingLevel", effort);
                }
                thinkingConfig.put("includeThoughts", !"none".equals(effort));
                generationConfig.put("thinkingConfig", thinkingConfig);
            }

            if (!generationConfig.isEmpty()) {
                finalBody.put("generationConfig", generationConfig);
            }

            if (request.getTools() != null && !request.getTools().isEmpty()) {
                List<Map<String, Object>> functionDeclarations = new ArrayList<>();
                for (ChatCompletionRequest.Tool tool : request.getTools()) {
                    if ("function".equals(tool.getType()) && tool.getFunction() != null) {
                        Map<String, Object> decl = new LinkedHashMap<>();
                        decl.put("name", tool.getFunction().getName());
                        decl.put("description", tool.getFunction().getDescription());
                        if (tool.getFunction().getParameters() != null) {
                            decl.put("parameters", tool.getFunction().getParameters());
                        }
                        functionDeclarations.add(decl);
                    }
                }
                if (!functionDeclarations.isEmpty()) {
                    finalBody.put("tools", Collections
                            .singletonList(Collections.singletonMap("function_declarations", functionDeclarations)));
                }
            }

            if (request.getToolChoice() != null) {
                Map<String, Object> toolConfig = new LinkedHashMap<>();
                Map<String, Object> functionCallingConfig = new LinkedHashMap<>();

                Object choice = request.getToolChoice();
                if ("none".equals(choice)) {
                    functionCallingConfig.put("mode", "NONE");
                } else if ("auto".equals(choice)) {
                    functionCallingConfig.put("mode", "AUTO");
                } else if ("required".equals(choice)) {
                    functionCallingConfig.put("mode", "ANY");
                } else if (choice instanceof Map) {
                    functionCallingConfig.put("mode", "ANY");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> choiceMap = (Map<String, Object>) choice;
                    Map<String, Object> func = (Map<String, Object>) choiceMap.get("function");
                    if (func != null && func.get("name") != null) {
                        functionCallingConfig.put("allowed_function_names",
                                Collections.singletonList(func.get("name")));
                    }
                }

                if (!functionCallingConfig.isEmpty()) {
                    toolConfig.put("function_calling_config", functionCallingConfig);
                    finalBody.put("tool_config", toolConfig);
                }
            }

            return objectMapper.writeValueAsString(finalBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Convert to Gemini failed", e);
        }
    }

    private void addParts(List<Map<String, Object>> targetParts, ChatCompletionMessage msg, String role,
            Map<String, String> toolCallIdToName) {
        Object content = msg.getContent();

        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            for (ChatCompletionMessage.ToolCall tc : msg.getToolCalls()) {
                if (tc.getFunction() != null) {
                    Map<String, Object> functionCall = new LinkedHashMap<>();
                    functionCall.put("name", tc.getFunction().getName());
                    try {
                        String args = tc.getFunction().getArguments();
                        if (args != null && !args.isEmpty()) {
                            functionCall.put("args", objectMapper.readTree(args));
                        } else {
                            functionCall.put("args", new HashMap<>());
                        }
                    } catch (Exception e) {
                        functionCall.put("args", new HashMap<>());
                    }
                    targetParts.add(Collections.singletonMap("functionCall", functionCall));
                    targetParts.add(Collections.singletonMap("thoughtSignature", geminiFunctionThoughtSignature));
                }
            }
        }

        if ("tool".equalsIgnoreCase(role)) {
            Map<String, Object> functionResponse = new LinkedHashMap<>();
            String functionName = toolCallIdToName != null ? toolCallIdToName.get(msg.getToolCallId()) : null;
            if (functionName == null) {
                functionName = msg.getToolCallId();
            }
            functionResponse.put("name", functionName);

            Map<String, Object> responseContent = new HashMap<>();
            if (content instanceof String) {
                try {
                    responseContent.put("content", objectMapper.readTree((String) content));
                } catch (Exception e) {
                    responseContent.put("content", content);
                }
            } else {
                responseContent.put("content", content);
            }
            functionResponse.put("response", responseContent);
            targetParts.add(Collections.singletonMap("functionResponse", functionResponse));
            return;
        }

        if (content == null)
            return;

        if (content instanceof String) {
            String text = (String) content;
            if (!text.isEmpty()) {
                targetParts.add(Collections.singletonMap("text", text));
            }
        } else if (content instanceof List) {
            List<Map<String, Object>> contentParts = (List<Map<String, Object>>) content;
            for (Map<String, Object> part : contentParts) {
                String type = (String) part.get("type");
                if ("text".equals(type)) {
                    String text = (String) part.get("text");
                    if (text != null && !text.isEmpty()) {
                        targetParts.add(Collections.singletonMap("text", text));
                    }
                } else if ("image_url".equals(type)) {
                    Map<String, String> imageUrlObj = (Map<String, String>) part.get("image_url");
                    String url = imageUrlObj != null ? imageUrlObj.get("url") : null;
                    if (url != null) {
                        if (url.startsWith("data:")) {
                            int commaIdx = url.indexOf(',');
                            if (commaIdx > 5) {
                                String header = url.substring(5, commaIdx);
                                String mimeType = header.split(";")[0];
                                String base64Data = url.substring(commaIdx + 1);
                                Map<String, Object> inlineData = new LinkedHashMap<>();
                                inlineData.put("mimeType", mimeType);
                                inlineData.put("data", base64Data);
                                targetParts.add(Collections.singletonMap("inlineData", inlineData));
                                targetParts.add(Collections.singletonMap("thoughtSignature", geminiFunctionThoughtSignature));
                            }
                        } else {
                            Map<String, Object> fileData = new LinkedHashMap<>();
                            fileData.put("mimeType", "image/jpeg");
                            fileData.put("fileUri", url);
                            targetParts.add(Collections.singletonMap("fileData", fileData));
                        }
                    }
                } else if ("file".equals(type)) {
                    Map<String, String> fileObj = (Map<String, String>) part.get("file");
                    if (fileObj != null) {
                        String fileData = fileObj.get("file_data");
                        String filename = fileObj.get("filename");
                        if (fileData != null) {
                            Map<String, Object> inlineData = new LinkedHashMap<>();
                            String mimeType = "application/octet-stream";
                            if (filename != null && filename.contains(".")) {
                                String ext = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
                                mimeType = switch (ext) {
                                    case "pdf" -> "application/pdf";
                                    case "png" -> "image/png";
                                    case "jpg", "jpeg" -> "image/jpeg";
                                    case "txt" -> "text/plain";
                                    default -> "application/octet-stream";
                                };
                            }
                            inlineData.put("mimeType", mimeType);
                            inlineData.put("data", fileData);
                            targetParts.add(Collections.singletonMap("inlineData", inlineData));
                        }
                    }
                }
            }
        }
    }

    private String convertToOpenAiChunk(String rawJson, String model) throws Exception {
        if (rawJson == null || rawJson.isBlank() || "[DONE]".equals(rawJson)) {
            return null;
        }

        JsonNode root = objectMapper.readTree(rawJson);

        String responseId = root.path("responseId").asText("chatcmpl-" + UUID.randomUUID());
        long created = System.currentTimeMillis() / 1000;
        if (root.has("createTime")) {
            try {
                String createTime = root.path("createTime").asText();
                created = java.time.Instant.parse(createTime).getEpochSecond();
            } catch (Exception ignored) {
            }
        }

        List<ChatCompletionResponse.Choice> choices = new ArrayList<>();
        JsonNode candidates = root.path("candidates");

        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                int index = candidate.path("index").asInt(0);
                String finishReason = mapFinishReason(candidate.path("finishReason").asText(null));

                ChatCompletionMessage delta = new ChatCompletionMessage();
                delta.setRole("assistant");

                JsonNode parts = candidate.at("/content/parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        if (part.has("text")) {
                            String text = part.get("text").asText();
                            if (part.path("thought").asBoolean(false)) {
                                delta.setReasoningContent(text);
                            } else {
                                delta.setContent(text);
                            }
                        } else if (part.has("functionCall")) {
                            JsonNode fc = part.get("functionCall");
                            ChatCompletionMessage.ToolCall tc = new ChatCompletionMessage.ToolCall();
                            tc.setId("call_" + UUID.randomUUID().toString().substring(0, 8));
                            tc.setType("function");

                            ChatCompletionMessage.FunctionCall fn = new ChatCompletionMessage.FunctionCall();
                            fn.setName(fc.path("name").asText());
                            fn.setArguments(fc.path("args").toString());

                            tc.setFunction(fn);
                            tc.setIndex(index);

                            if (delta.getToolCalls() == null) {
                                delta.setToolCalls(new ArrayList<>());
                            }
                            delta.getToolCalls().add(tc);
                        }
                    }
                }

                if (delta.getContent() == null && delta.getReasoningContent() == null && finishReason == null) {
                    continue;
                }

                choices.add(ChatCompletionResponse.Choice.builder()
                        .index(index)
                        .delta(delta)
                        .finishReason(finishReason)
                        .build());
            }
        }

        ChatCompletionResponse.Usage usage = null;
        JsonNode usageNode = root.path("usageMetadata");
        if (!usageNode.isMissingNode()) {
            int promptTokens = usageNode.path("promptTokenCount").asInt(0);
            int candidatesTokens = usageNode.path("candidatesTokenCount").asInt(0);
            int totalTokens = usageNode.path("totalTokenCount").asInt(0);
            int cachedTokens = usageNode.path("cachedContentTokenCount").asInt(0);
            int reasoningTokens = usageNode.path("thoughtsTokenCount").asInt(0);

            usage = ChatCompletionResponse.Usage.builder()
                    .promptTokens(promptTokens)
                    .completionTokens(candidatesTokens)
                    .totalTokens(totalTokens)
                    .promptTokensDetails(cachedTokens > 0
                            ? ChatCompletionResponse.PromptTokensDetails.builder().cachedTokens(cachedTokens).build()
                            : null)
                    .completionTokensDetails(reasoningTokens > 0
                            ? ChatCompletionResponse.CompletionTokensDetails.builder().reasoningTokens(reasoningTokens).build()
                            : null)
                    .build();
        }

        if (choices.isEmpty() && usage == null) {
            return null;
        }

        ChatCompletionResponse chunk = ChatCompletionResponse.builder()
                .id(responseId)
                .object("chat.completion.chunk")
                .created(created)
                .model(model)
                .choices(choices)
                .usage(usage)
                .build();

        return objectMapper.writeValueAsString(chunk);
    }

    private String mapFinishReason(String geminiReason) {
        if (geminiReason == null)
            return null;
        return switch (geminiReason.toUpperCase()) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION" -> "content_filter";
            default -> geminiReason.toLowerCase();
        };
    }

    private long queryToken(String json, Pattern pattern) {
        Matcher m = pattern.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }
}
