package com.openrouter.model;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    private Boolean stream;
    private Double temperature;
    
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("stream_options")
    private StreamOptions streamOptions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamOptions {
        @JsonProperty("include_usage")
        private Boolean includeUsage;
    }
}
