package com.openrouter.metrics;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * 上游调用失败的类型划分。
 * 不同类型对渠道健康度的含义不同：瞬态错误（限流/超时/5xx）可自愈，
 * 硬失败（鉴权）说明配置有问题，而 4xx（除 429）属于请求侧问题，不应归罪渠道。
 */
public enum ErrorKind {

    /** 鉴权失败（401/403）：几乎不可能通过重试自愈 */
    AUTH,
    /** 请求侧错误（其余 4xx）：请求本身的问题，不计入渠道健康度 */
    CLIENT,
    /** 限流（429）：瞬态，但说明渠道容量紧张 */
    RATE_LIMIT,
    /** 上游 5xx：瞬态 */
    SERVER,
    /** 读/连接超时 */
    TIMEOUT,
    /** 连接被拒 / DNS 失败 / 连接中断 */
    NETWORK,
    /** 其他未识别错误 */
    UNKNOWN;

    /**
     * 计入健康度失败率时的权重。
     * AUTH 加倍（配置问题需要更强的信号），CLIENT 豁免（与渠道无关）。
     */
    public double healthWeight() {
        return switch (this) {
            case CLIENT -> 0.0;
            case AUTH -> 2.0;
            default -> 1.0;
        };
    }

    /** 按异常类型与 HTTP 状态码判定错误种类 */
    public static ErrorKind classify(Throwable e) {
        Throwable cause = e;
        for (int depth = 0; depth < 6 && cause != null; depth++) {
            if (cause instanceof WebClientResponseException resp) {
                int status = resp.getStatusCode().value();
                if (status == 401 || status == 403) return AUTH;
                if (status == 429) return RATE_LIMIT;
                if (status >= 500) return SERVER;
                if (status >= 400) return CLIENT;
                return UNKNOWN;
            }
            if (isTimeout(cause)) return TIMEOUT;
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) return NETWORK;
            if (cause instanceof WebClientRequestException) {
                // 请求异常通常只是包装层，继续向内看具体原因
                Throwable inner = cause.getCause();
                if (inner == null) return NETWORK;
                cause = inner;
                continue;
            }
            cause = cause.getCause();
        }
        return UNKNOWN;
    }

    /** 用类名判断而非直接引用 Netty 类型，避免对底层 HTTP 客户端实现的硬依赖 */
    private static boolean isTimeout(Throwable t) {
        return t instanceof TimeoutException || t.getClass().getName().contains("Timeout");
    }
}
