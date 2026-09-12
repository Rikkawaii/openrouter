package com.openrouter.trace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 全链路追踪日志门面。
 * 对标 Python 版 TraceLogger：
 * [HH:mm:ss.SSS] 【阶段】 状态 (耗时: Xms) [重试: N] | 详情 <traceId>
 *
 * 同时维护一个环形文本缓冲区（最多 10000 行），为将来的 WebSocket 推送能力预留。
 */
@Slf4j
@Service
public class TraceLogger {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_BUFFER = 1000;

    // ⚡ 实时日志推送 Sink (重播模式：自动缓存最近 1000 条并在订阅时回放，解决并发下的丢失与重复问题)
    private final Sinks.Many<String> logSink = Sinks.many().replay().limit(MAX_BUFFER);

    /**
     * 重载 log：最简化的日志记录，自动加时间戳并推送到终端。
     * 默认级别为 INFO。
     */
    public void log(String msg) {
        log("INFO", msg);
    }

    /**
     * 重载 log：支持指定日志级别，自动加时间戳并推送到终端。
     * 
     * @param level "INFO" | "WARN" | "ERROR" | "SUCCESS"
     */
    public void log(String level, String msg) {
        String localTime = LocalDateTime.now(ZONE).format(TIME_FMT);
        String formatted = String.format("[%s] [%s] %s", localTime, level, msg);

        // 1. 打印到真实控制台
        if ("ERROR".equalsIgnoreCase(level) || "WARN".equalsIgnoreCase(level)) {
            log.warn(formatted);
        } else {
            log.info(formatted);
        }

        // 2. 存入缓冲区并分发
        pushToTerminal(formatted);
    }

    /**
     * 核心方法：追加一条追踪事件并格式化打印到控制台。
     *
     * @param ctx     本次请求的追踪上下文
     * @param stage   阶段 key，如 "FIRST_TOKEN"
     * @param status  "success" | "fail" | "error"
     * @param details 补充详情（可为 null 或空）
     */
    public void log(RequestTraceContext ctx, String stage, String status, String details) {
        if (ctx == null)
            return;

        // 1. 向 ctx 追加事件（含自动计时）
        ctx.addEvent(stage, status, details);

        // 2. 取出刚追加的最后一条事件做格式化
        var events = ctx.getEvents();
        TraceEvent event = events.get(events.size() - 1);

        // 3. 格式化文本
        String formatted = format(ctx.getTraceId(), event);

        // 4. Slf4j 打印 (用于文件日志)
        if ("fail".equals(status) || "error".equals(status)) {
            log.warn(formatted);
        } else {
            log.info(formatted);
        }

        // 5. 推送到 WebSocket 终端
        pushToTerminal(formatted);
    }

    /**
     * 在请求最开始打印一条分隔线。
     */
    public void separator(String traceId) {
        String line = "─".repeat(60) + " <" + traceId + ">";
        log.info(line);
        pushToTerminal(line);
    }

    /**
     * 将格式化后的日志行分发。
     */
    private void pushToTerminal(String formattedLine) {
        // 异步分发给所有 WebSocket 订阅者 (Sink 内部已处理缓存和安全性)
        logSink.tryEmitNext(formattedLine);
    }

    /**
     * 获取当前的实时日志流。
     */
    public Flux<String> getLogStream() {
        return logSink.asFlux();
    }

    /**
     * 获取历史缓冲区快照 (通常由 replay sink 自动处理，仅在必要时保留此接口)。
     */
    // public List<String> getRecentLogs() {
    // // 由于极大的并发性能考虑，不建议在此直接拉取 replay 内部队列，
    // // 建议始终通过 getLogStream() 交给订阅者自行处理。
    // return new ArrayList<>();
    // }

    // ── 格式化工具 ──────────────────────────────────────────────────────────

    private String format(String traceId, TraceEvent event) {
        String localTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(event.getTimestampMs()), ZONE).format(TIME_FMT);

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(localTime).append("] ");
        sb.append("【").append(event.getStageLabel()).append("】 ");
        sb.append(translateStatus(event.getStatus()));

        if (event.getDurationMs() > 0) {
            sb.append(" (耗时: ").append(event.getDurationMs()).append("ms)");
        }
        if (event.getRetryCount() > 0) {
            sb.append(" [重试: ").append(event.getRetryCount()).append("]");
        }
        if (event.getDetails() != null && !event.getDetails().isBlank()) {
            sb.append(" | ").append(event.getDetails());
        }
        sb.append(" <").append(traceId).append(">");

        return sb.toString();
    }

    private String translateStatus(String status) {
        return switch (status) {
            case "success" -> "成功";
            case "fail" -> "失败";
            case "error" -> "错误";
            default -> status;
        };
    }
}
