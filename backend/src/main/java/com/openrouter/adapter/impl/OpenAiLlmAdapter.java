package com.openrouter.adapter.impl;

import com.openrouter.adapter.LlmClientAdapter;
import com.openrouter.config.ChannelConfig;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.metrics.ModelMetrics;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.model.ChatCompletionResponse;
import com.openrouter.service.UsageDatabaseService;
import com.openrouter.trace.RequestTraceContext;
import com.openrouter.trace.TraceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class OpenAiLlmAdapter implements LlmClientAdapter {

    private final WebClient webClient;
    private final MetricsRegistry metricsRegistry;
    private final TraceLogger traceLogger;
    private final UsageDatabaseService usageDatabaseService;

    private static final Pattern PROMPT_PATTERN = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_PATTERN = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");

    public OpenAiLlmAdapter(WebClient routerWebClient, MetricsRegistry metricsRegistry,
            TraceLogger traceLogger, UsageDatabaseService usageDatabaseService) {
        this.webClient = routerWebClient;
        this.metricsRegistry = metricsRegistry;
        this.traceLogger = traceLogger;
        this.usageDatabaseService = usageDatabaseService;
    }

    @Override
    public String getProtocolType() {
        return "openai";
    }

    @Override
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request, ChannelConfig channel) {
        request.setStream(false);

        ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
        RequestTraceContext ctx = request.getTraceContext();
        // 计时起点与流式保持一致：Flux 被订阅、真正发起上游请求时才取
        AtomicLong startTime = new AtomicLong(0);

        return webClient.post()
                .uri(buildUrl(channel.getBaseUrl(), "/chat/completions"))
                .header("Authorization", "Bearer " + channel.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .doOnSubscribe(s -> {
                    startTime.set(System.currentTimeMillis());
                    metrics.beginCall(request.getModel());
                    if (ctx != null) {
                        ctx.setModel(request.getModel());
                        ctx.setChannelId(channel.getId());
                    }
                })
                .doOnNext(res -> {
                    long duration = System.currentTimeMillis() - startTime.get();
                    // 非流式没有首包时刻，ttft 记为 NULL
                    usageDatabaseService.saveCallLogAsync(ctx, channel.getId(), request.getModel(), duration, true, null, null);

                    long p = 0, c = 0;
                    if (res.getUsage() != null) {
                        p = res.getUsage().getPromptTokens();
                        c = res.getUsage().getCompletionTokens();
                        metrics.addTokens(request.getModel(), p, c);
                    }
                    if (ctx != null) {
                        ctx.setPromptTokens(p);
                        ctx.setCompletionTokens(c);
                        ctx.setFullResponseJson(
                                "{\"usage\":{\"prompt_tokens\":" + p + ",\"completion_tokens\":" + c + "}}");
                    }
                    // FULL_RESPONSE 由 Service 层统一收尾时记录
                    metrics.recordSuccess(request.getModel(), duration);
                })
                .doOnError(e -> {
                    long duration = System.currentTimeMillis() - startTime.get();
                    usageDatabaseService.saveCallLogAsync(ctx, channel.getId(), request.getModel(), duration, false, e.getMessage(), null);
                    // 错误不在此处记录日志：Service 层的 onErrorResume 会触发 MODEL_FAIL，流转下一个渠道重试
                    metrics.recordFailure(request.getModel(), e);
                })
                .doFinally(sig -> metrics.endCall());
    }

    @Override
    public Flux<String> streamChat(ChatCompletionRequest request, ChannelConfig channel) {
        request.setStream(true);
        if (request.getStreamOptions() == null) {
            request.setStreamOptions(ChatCompletionRequest.StreamOptions.builder().includeUsage(true).build());
        }

        ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
        RequestTraceContext ctx = request.getTraceContext();
        
        final String targetModel = request.getModel(); // 🧊 抓取当前模型快照，防止异步副作用
        final String targetChannelId = channel.getId();

        return webClient.post()
                .uri(channel.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + channel.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnSubscribe(s -> {
                    metrics.beginCall(targetModel);
                    // 🧊 最早时机注入：在任何 doFinally 触发之前，model/channelId 已就绪
                    if (ctx != null) {
                        ctx.setModel(targetModel);
                        ctx.setChannelId(targetChannelId);
                    }
                })
                .transform(flux -> {
                    long startTime = System.currentTimeMillis();
                    AtomicBoolean first = new AtomicBoolean(true);
                    AtomicLong lastP = new AtomicLong(0);
                    AtomicLong lastC = new AtomicLong(0);
                    AtomicReference<Long> ttftMs = new AtomicReference<>(null);

                    return flux.doOnNext(item -> {
                        if (first.compareAndSet(true, false)) {
                            // 首包延迟：从发出上游请求到第一个响应块到达
                            Long ttft = System.currentTimeMillis() - startTime;
                            ttftMs.set(ttft);
                            // 立即写入 ctx：Service 层的收尾（落库/全链路计数）早于本层 doFinally 执行
                            if (ctx != null) {
                                ctx.setTtftMs(ttft);
                                traceLogger.log(ctx, "FIRST_TOKEN", "success", "");
                            }
                        }
                        if (item.contains("\"usage\":{")) {
                            try {
                                Matcher m1 = PROMPT_PATTERN.matcher(item);
                                Matcher m2 = COMPLETION_PATTERN.matcher(item);
                                if (m1.find() && m2.find()) {
                                    long p = Long.parseLong(m1.group(1));
                                    long c = Long.parseLong(m2.group(1));
                                    metrics.addTokens(targetModel, p, c);
                                    lastP.set(p);
                                    lastC.set(c);
                                    if (ctx != null) {
                                        ctx.setPromptTokens(p);
                                        ctx.setCompletionTokens(c);
                                        ctx.setFullResponseJson(
                                                "{\"usage\":{\"prompt_tokens\":" + p + ",\"completion_tokens\":" + c + "}}");
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    })
                            .doFinally(sig -> {
                                long callDuration = System.currentTimeMillis() - startTime;
                                boolean success = (sig == SignalType.ON_COMPLETE);
                                Long ttft = ttftMs.get();
                                // model_call_log：每次底层尝试都记录（非流式/首包前失败 ttft 为 NULL）
                                usageDatabaseService.saveCallLogAsync(ctx, targetChannelId, targetModel, callDuration, success, sig == SignalType.ON_ERROR ? "Stream error" : null, ttft);
                                if (success) {
                                    // request_log / FULL_RESPONSE 由 Service 层统一收尾
                                    metrics.recordStreamSuccess(targetModel, callDuration, ttft);
                                }
                            });
                })
                .doOnError(e -> metrics.recordFailure(targetModel, e))
                .doFinally(sig -> metrics.endCall());
    }

    public String buildUrl(String baseUrl, String path) {
        if (baseUrl == null)
            return path;
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;
    }
}
