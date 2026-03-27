package com.openrouter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 路由网关独立配置的高性能 WebClient（底层基于 Netty 非阻塞 HTTP 客户端）。
 */
@Configuration
public class WebClientConfig {

    @Bean("routerWebClient")
    public WebClient routerWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120)); // 大模型长响应预留超时说明

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
