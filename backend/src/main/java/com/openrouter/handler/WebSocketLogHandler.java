package com.openrouter.handler;

import com.openrouter.trace.TraceLogger;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * 实时日志推送处理器。
 * 当 WebSocket 连接建立时，先推送历史缓冲区中的 1000 条日志，随后持续推送实时增量。
 */
@Component
public class WebSocketLogHandler implements WebSocketHandler {

    private final TraceLogger traceLogger;

    public WebSocketLogHandler(TraceLogger traceLogger) {
        this.traceLogger = traceLogger;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // 使用 replay sink 模式，直接订阅即可同时获取历史与实时流，保证原子传输
        return session.send(
                traceLogger.getLogStream().map(session::textMessage)
        ).then();
    }
}
