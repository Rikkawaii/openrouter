package com.openrouter.trace;

import lombok.Data;

import java.util.LinkedHashMap;

/**
 * 代表请求生命周期中一个关键阶段的追踪事件快照。
 * 与 Python TraceLogger 的 _format_log 数据模型对齐。
 */
@Data
public class TraceEvent {

    private static final java.util.Map<String, String> STAGE_MAPPING = new LinkedHashMap<>() {{
        put("REQ_RECEIVED",        "收到请求");
        put("MENTOR_RULE_APPLIED", "导师规则触发");
        put("MODEL_CALL_START",    "开始调用模型");
        put("FIRST_TOKEN",         "首字返回");
        put("FULL_RESPONSE",       "响应完成");
        put("MODEL_FAIL",          "模型调用失败");
        put("ALL_FAILED",          "全部尝试失败");
    }};

    /** 阶段标识 key，如 "MODEL_CALL_START" */
    private final String stage;

    /** 阶段中文描述，如 "开始调用模型" */
    private final String stageLabel;

    /** 事件发生时的绝对时间戳 (epoch ms) */
    private final long timestampMs;

    /**
     * 距离上一个 TraceEvent（或请求起始时刻）的相对耗时 (ms)。
     * REQ_RECEIVED 阶段该值为 0。
     */
    private final long durationMs;

    /** "success" | "fail" | "error" */
    private final String status;

    /** 当前事件对应的重试次数（首次 = 0） */
    private final int retryCount;

    /** 补充详情：渠道名、模型名、错误信息、ttft 等 */
    private final String details;

    /**
     * 工厂方法，根据 stage key 自动映射中文标签。
     */
    public static TraceEvent of(String stage, long timestampMs, long durationMs,
                                String status, int retryCount, String details) {
        String label = STAGE_MAPPING.getOrDefault(stage, stage);
        return new TraceEvent(stage, label, timestampMs, durationMs, status, retryCount, details);
    }
}
