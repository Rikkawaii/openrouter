package com.openrouter.config;

import com.openrouter.handler.WebSocketLogHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 配置类。
 * 将 /api/admin/logs/ws 路径映射到 WebSocketLogHandler。
 */
@Configuration
public class WebSocketConfig {

    private final WebSocketLogHandler webSocketLogHandler;

    public WebSocketConfig(WebSocketLogHandler webSocketLogHandler) {
        this.webSocketLogHandler = webSocketLogHandler;
    }

    @Bean
    public HandlerMapping webSocketHandlerMapping() {
        Map<String, Object> map = new HashMap<>();
        map.put("/api/admin/logs/ws", webSocketLogHandler);

        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(1);
        handlerMapping.setUrlMap(map);
        return handlerMapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
