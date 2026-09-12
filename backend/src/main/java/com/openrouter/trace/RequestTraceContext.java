package com.openrouter.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单次请求的全链路追踪上下文容器。
 * 通过 ChatCompletionRequest 的 @JsonIgnore 字段透明地在整条调用链中传递，
 * 无需修改任何方法签名。
 */
@Slf4j
@Getter
public class RequestTraceContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 请求唯一 ID（UUID 前 8 位） */
    private final String traceId;

    /** 请求到达 Controller 时的系统时间戳 (epoch ms) */
    private final long requestStartTime;

    /** 各阶段追踪事件列表 */
    private final List<TraceEvent> events = new ArrayList<>();

    /** 全局重试次数（每次 onErrorResume 发生时+1） */
    @Setter
    private int retryCount = 0;

    /** 原始入站请求体 JSON（在 Controller 入口序列化存入） */
    @Setter
    private String fullRequestJson;

    /** 最终成功的渠道 ID */
    @Setter
    private String channelId;

    /** 最终成功的具体模型名 */
    @Setter
    private String model;

    /** 累计消耗的 Prompt Tokens */
    @Setter
    private long promptTokens = 0;

    /** 累计消耗的 Completion Tokens */
    @Setter
    private long completionTokens = 0;

    /** 最终模型响应 JSON（由 Adapter 在落库前写入） */
    @Setter
    private String fullResponseJson;

    public RequestTraceContext() {
        this.traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.requestStartTime = System.currentTimeMillis();
    }

    /**
     * 追加一个追踪事件。
     * durationMs 自动计算为距上一个事件（或请求起始）的相对耗时。
     *
     * @param stage   阶段 key，如 "MODEL_CALL_START"
     * @param status  "success" | "fail" | "error"
     * @param details 补充详情
     */
    public void addEvent(String stage, String status, String details) {
        long now = System.currentTimeMillis();
        long lastTimestamp = events.isEmpty() ? requestStartTime : events.get(events.size() - 1).getTimestampMs();
        long duration = now - lastTimestamp;
        events.add(TraceEvent.of(stage, now, duration, status, retryCount, details));
    }

    /**
     * 将 events 序列化为 JSON 字符串，用于落库。
     */
    public String toEventsJson() {
        try {
            return MAPPER.writeValueAsString(events);
        } catch (Exception e) {
            log.error("[TraceLogger] 序列化 trace_events 失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 返回从请求起始到现在的总耗时 (ms)。
     */
    public long getTotalDurationMs() {
        return System.currentTimeMillis() - requestStartTime;
    }
}
