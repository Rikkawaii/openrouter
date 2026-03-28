package com.openrouter.adapter.impl;

import com.openrouter.adapter.LlmClientAdapter;
import com.openrouter.config.RouterProperties;
import com.openrouter.metrics.MetricsRegistry;
import com.openrouter.metrics.ModelMetrics;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class OpenAiLlmAdapter implements LlmClientAdapter {

    private final WebClient webClient;
    private final MetricsRegistry metricsRegistry;
    private static final Pattern PROMPT_PATTERN = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");

    private static final Pattern COMPLETION_PATTERN = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");

    public OpenAiLlmAdapter(WebClient routerWebClient, MetricsRegistry metricsRegistry) {
        this.webClient = routerWebClient;
        this.metricsRegistry = metricsRegistry;
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
                    metrics.recordLatency(System.currentTimeMillis() - startTime);
                    if (res.getUsage() != null) {
                        metrics.addTokens(request.getModel(),
                                res.getUsage().getPromptTokens(),
                                res.getUsage().getCompletionTokens());
                    }
                })
                .doOnError(e -> {
                    System.out.print("出现错误");
                    metrics.recordError();
                })
                .doFinally(sig -> metrics.decrementConcurrent());
        // data:{"id":"e880091b-3932-4567-83ad-bd88d17d2d25","object":"chat.completion.chunk","created":1774629629,"model":"deepseek-reasoner","system_fingerprint":"fp_eaab8d114b_prod0820_fp8_kvcache_new_kvcache","choices":[{"index":0,"delta":{"content":"","reasoning_content":null},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":463,"total_tokens":471,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":100},"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":8}}

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
                    return flux.doOnNext(item -> {
                        if (first.compareAndSet(true, false)) {
                            metrics.recordLatency(System.currentTimeMillis() - startTime);
                        }
                        log.info(item);
                        if (item.contains("\"usage\":{")) {
                            try {
                                // 提取 prompt_tokens
                                Matcher m1 = PROMPT_PATTERN.matcher(item);
                                // 提取 completion_tokens
                                Matcher m2 = COMPLETION_PATTERN.matcher(item);
                                if (m1.find() && m2.find()) {
                                    metrics.addTokens(request.getModel(),
                                            Long.parseLong(m1.group(1)),
                                            Long.parseLong(m2.group(1)));
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    });
                })
                .doOnError(e -> {
                    metrics.recordError();
                })
                .doFinally(sig -> metrics.decrementConcurrent());
    }
}
