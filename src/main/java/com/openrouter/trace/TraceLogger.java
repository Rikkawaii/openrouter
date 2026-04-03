package com.openrouter.trace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 全链路追踪日志门面。
 * 对标 Python 版 TraceLogger：
 *   [HH:mm:ss.SSS] 【阶段】 状态 (耗时: Xms) [重试: N] | 详情 <traceId>
 *
 * 同时维护一个环形文本缓冲区（最多 10000 行），为将来的 WebSocket 推送能力预留。
 */
@Slf4j
@Service
public class TraceLogger {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_BUFFER = 10_000;

    private final Deque<String> buffer = new ArrayDeque<>(MAX_BUFFER);

    /**
     * 核心方法：追加一条追踪事件并格式化打印到控制台。
     *
     * @param ctx     本次请求的追踪上下文
     * @param stage   阶段 key，如 "FIRST_TOKEN"
     * @param status  "success" | "fail" | "error"
     * @param details 补充详情（可为 null 或空）
     */
    public void log(RequestTraceContext ctx, String stage, String status, String details) {
        if (ctx == null) return;

        // 1. 向 ctx 追加事件（含自动计时）
        ctx.addEvent(stage, status, details);

        // 2. 取出刚追加的最后一条事件做格式化
        var events = ctx.getEvents();
        TraceEvent event = events.get(events.size() - 1);

        // 3. 格式化文本，对标 Python 版 _format_log
        String formatted = format(ctx.getTraceId(), event);

        // 4. Slf4j 打印控制台
        if ("fail".equals(status) || "error".equals(status)) {
            log.warn(formatted);
        } else {
            log.info(formatted);
        }

        // 5. 存入环形缓冲区
        synchronized (buffer) {
            if (buffer.size() >= MAX_BUFFER) {
                buffer.pollFirst();
            }
            buffer.addLast(formatted);
        }
    }

    /**
     * 在请求最开始打印一条分隔线，便于在控制台中区分不同的请求。
     */
    public void separator(String traceId) {
        String line = "─".repeat(60) + " <" + traceId + ">";
        log.info(line);
        synchronized (buffer) {
            if (buffer.size() >= MAX_BUFFER) buffer.pollFirst();
            buffer.addLast(line);
        }
    }

    // ── 格式化工具 ──────────────────────────────────────────────────────────

    private String format(String traceId, TraceEvent event) {
        String localTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(event.getTimestampMs()), ZONE
        ).format(TIME_FMT);

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
            case "fail"    -> "失败";
            case "error"   -> "错误";
            default        -> status;
        };
    }
}
