package com.openrouter.filter;

import com.openrouter.config.RouterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * API Key 鉴权过滤器。
 * 在请求到达 Controller 前拦截，对 /v1/** 路径执行 Bearer Token 校验。
 * 使用 WebFilter 而非 AOP，因为 WebFlux 是响应式栈，WebFilter 是其原生拦截机制。
 */
@Slf4j
@Component
@Order(-100) // 越小越先执行，确保在其他过滤器之前鉴权
public class ApiKeyAuthFilter implements WebFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final byte[] UNAUTHORIZED_BODY =
            "{\"error\":\"Unauthorized access to router endpoint.\"}".getBytes(StandardCharsets.UTF_8);

    private final RouterProperties routerProperties;

    public ApiKeyAuthFilter(RouterProperties routerProperties) {
        this.routerProperties = routerProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 只拦截 /v1/** 路径
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange);
        }

        String expectedKey = routerProperties.getApiKey();
        // 未配置 API Key，放行（开发模式）
        if (!StringUtils.hasText(expectedKey)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(AUTH_HEADER);
        String providedKey = authHeader != null && authHeader.startsWith(BEARER_PREFIX)
                ? authHeader.substring(BEARER_PREFIX.length()).trim()
                : null;

        if (providedKey == null || !providedKey.equals(expectedKey.trim())) {
            log.warn("🚨 拦截到未授权请求: path={}, authHeader={}", path, authHeader);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            var buffer = exchange.getResponse().bufferFactory().wrap(UNAUTHORIZED_BODY);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        return chain.filter(exchange);
    }
}
