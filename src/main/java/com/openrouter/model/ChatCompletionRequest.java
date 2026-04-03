package com.openrouter.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.openrouter.trace.RequestTraceContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatCompletionRequest {
    private String model;
    private List<ChatCompletionMessage> messages;

    /** 请求级别的全链路追踪上下文，由 Controller 创建并随 request 对象传递，不参与 JSON 序列化 */
    @JsonIgnore
    private RequestTraceContext traceContext;
    private Boolean stream;
    private Double temperature;
    
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("top_k")
    private Integer topK;

    private Integer n;

    @JsonProperty("reasoning_effort")
    private String reasoningEffort;

    @JsonProperty("stream_options")
    private StreamOptions streamOptions;

    private List<Tool> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamOptions {
        @JsonProperty("include_usage")
        private Boolean includeUsage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tool {
        private String type;
        private FunctionDefinition function;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FunctionDefinition {
        private String name;
        private String description;
        private Object parameters;
        private Boolean strict;
    }
}
