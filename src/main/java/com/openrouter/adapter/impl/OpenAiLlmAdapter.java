package com.openrouter.adapter.impl;

import com.openrouter.adapter.LlmClientAdapter;
import com.openrouter.config.RouterProperties;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.metrics.ModelMetrics;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.model.ChatCompletionResponse;
import com.openrouter.service.UsageLogger;
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
    private final UsageLogger usageLogger;
    
    private static final Pattern PROMPT_PATTERN = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_PATTERN = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");

    public OpenAiLlmAdapter(WebClient routerWebClient, MetricsRegistry metricsRegistry, UsageLogger usageLogger) {
        this.webClient = routerWebClient;
        this.metricsRegistry = metricsRegistry;
        this.usageLogger = usageLogger;
    }

    @Override
    public String getProtocolType() {
        return "openai";
    }

    @Override
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request, RouterProperties.Channel channel) {
        request.setStream(false);
        // 模型代号已经在 Service 层装配完毕

        ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
        long startTime = System.currentTimeMillis();

        return webClient.post()
                .uri(buildUrl(channel.getBaseUrl(), "/v1/chat/completions"))
                .header("Authorization", "Bearer " + channel.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .doOnSubscribe(s -> {
                    metrics.incrementConcurrent();
                    metrics.recordCall();
                })
                .doOnNext(res -> {
                    long duration = System.currentTimeMillis() - startTime;
                    metrics.recordTotalDuration(duration);
                    long p = 0, c = 0;
                    if (res.getUsage() != null) {
                        p = res.getUsage().getPromptTokens();
                        c = res.getUsage().getCompletionTokens();
                        metrics.addTokens(request.getModel(), p, c);
                    }
                    usageLogger.recordLogAsync(channel.getId(), request.getModel(), p, c, 0, duration, true, null);
                })
                .doOnError(e -> {
                    metrics.recordError();
                    long duration = System.currentTimeMillis() - startTime;
                    usageLogger.recordLogAsync(channel.getId(), request.getModel(), 0, 0, duration, duration, false, e.getMessage());
                })
                .doFinally(sig -> metrics.decrementConcurrent());
    }

    @Override
    public Flux<String> streamChat(ChatCompletionRequest request, RouterProperties.Channel channel) {
        request.setStream(true);
        // 模型代号已经在 Service 层装配完毕
        if (request.getStreamOptions() == null) {
            request.setStreamOptions(ChatCompletionRequest.StreamOptions.builder().includeUsage(true).build());
        }

        ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
        // 设置了响应头为MediaType.TEXT_EVENT_STREAM,则会自行将响应内容的"data"以及换行符去除，拿到每个data的json以流的形式返回给controller
        // 如果controller设置了返回类型为MediaType.TEXT_EVENT_STREAM,则会把每个流数据再次使用data包装！（以此返回原生的openai
        // sse流格式）
        return webClient.post()
                .uri(channel.getBaseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + channel.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnSubscribe(s -> {
                    metrics.incrementConcurrent();
                    metrics.recordCall();
                })
                .transform(flux -> {
                    long startTime = System.currentTimeMillis();
                    AtomicBoolean first = new AtomicBoolean(true);
                    AtomicLong ttft = new AtomicLong(0);
                    AtomicLong lastP = new AtomicLong(0);
                    AtomicLong lastC = new AtomicLong(0);
                    AtomicReference<String> errorMsg = new AtomicReference<>(null);

                    return flux.doOnNext(item -> {
                        if (first.compareAndSet(true, false)) {
                            long firstLat = System.currentTimeMillis() - startTime;
                            ttft.set(firstLat);
                            metrics.recordLatency(firstLat);
                        }
                        if (item.contains("\"usage\":{")) {
                            try {
                                Matcher m1 = PROMPT_PATTERN.matcher(item);
                                Matcher m2 = COMPLETION_PATTERN.matcher(item);
                                if (m1.find() && m2.find()) {
                                    long p = Long.parseLong(m1.group(1));
                                    long c = Long.parseLong(m2.group(1));
                                    metrics.addTokens(request.getModel(), p, c);
                                    lastP.set(p);
                                    lastC.set(c);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .doOnError(e -> errorMsg.set(e.getMessage()))
                    .doFinally(sig -> {
                        long totalDuration = System.currentTimeMillis() - startTime;
                        metrics.recordTotalDuration(totalDuration);
                        boolean success = sig != SignalType.ON_ERROR;
                        usageLogger.recordLogAsync(channel.getId(), request.getModel(), lastP.get(), lastC.get(), 
                                                   ttft.get(), totalDuration, success, errorMsg.get());
                    });
                })
                .doOnError(e -> {
                    metrics.recordError();
                })
                .doFinally(sig -> metrics.decrementConcurrent());
    }
}
