package com.openrouter.adapter;

import com.openrouter.config.RouterProperties;
import com.openrouter.model.ChatCompletionRequest;
import com.openrouter.model.ChatCompletionResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 大客户端全响应式、无状态协议基座！
 * 取消内部绑定 Key 和 BaseUrl，纯接参打工组件。
 */
public interface LlmClientAdapter {

    /**
     * 该适配器支持解析什么种类的协议类型
     * 例如 "openai", "gemini"
     */
    String getProtocolType();

    /**
     * @param request 标准模型化请求
     * @param channel 路由器分配下来干活的具体渠道网络和模型参数
     */
    Mono<ChatCompletionResponse> chat(ChatCompletionRequest request, RouterProperties.Channel channel);

    Flux<String> streamChat(ChatCompletionRequest request, RouterProperties.Channel channel);

    default String buildUrl(String baseUrl, String path) {
        try {
            URI baseUri = new URI(baseUrl);
            URI resolvedUri = baseUri.resolve(path);
            return resolvedUri.toString();
        }catch (Exception e){
            return "123";
        }
    }
}

