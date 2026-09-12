package com.openrouter.config;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class AdminAuthFilter implements WebFilter {

    private final RouterProperties routerProperties;

    public AdminAuthFilter(RouterProperties routerProperties) {
        this.routerProperties = routerProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 只有 /api/admin/** 需要拦截
        if (!path.startsWith("/api/admin/")) {
            return chain.filter(exchange);
        }

        // 放行登录接口
        if (path.equals("/api/admin/login")) {
            return chain.filter(exchange);
        }

        // 放行 WebSocket (日志流) 端点，WS 握手鉴权通常较复杂，这里简单起见先放行或者也检查 Token
        // 如果是通过浏览器直接连 WS，可能无法带自定义 Header，这里我们先简单放行或者让前端在 URL 带参数
        if (path.equals("/api/admin/logs/ws")) {
             return chain.filter(exchange);
        }

        // 校验 Header: Authorization 或 X-Admin-Token
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (!StringUtils.hasText(token)) {
            token = exchange.getRequest().getHeaders().getFirst("X-Admin-Token");
        }

        String actualPassword = routerProperties.getAdminPassword();
        
        // 如果密码没配置，默认公开（安全起见也可以默认拒绝，这里我们走配置优先）
        if (!StringUtils.hasText(actualPassword)) {
            return chain.filter(exchange);
        }

        if (StringUtils.hasText(token) && token.equals(actualPassword)) {
            return chain.filter(exchange);
        }

        // 鉴权失败
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\": false, \"message\": \"Unauthorized: Please Login\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
