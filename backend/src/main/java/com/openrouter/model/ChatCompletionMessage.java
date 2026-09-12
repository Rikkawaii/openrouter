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
public class ChatCompletionMessage {
    private String role;

    /**
     * 可以是纯字符串("Hello!")，或者多模态内容数组。
     * 为了兼容两种格式，这里使用 Object 类型，Jackson 自动解析。
     * 纯文本时: "Hello!"
     * 多模态时: [{"type":"text","text":"..."},
     * {"type":"image_url","image_url":{"url":"..."}}]
     */
    private Object content;

    @JsonProperty("reasoning_content")
    private String reasoningContent;

    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    @JsonProperty("tool_call_id")
    private String toolCallId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String id;
        private String type;
        private FunctionCall function;
        private Integer index;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FunctionCall {
        private String name;
        private String arguments;
    }

    // ==================== 多模态内容块类型定义 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentPart {
        /** "text" | "image_url" | "input_audio" | "file" */
        private String type;

        /** 当 type="text" 时使用 */
        private String text;

        /** 当 type="image_url" 时使用 */
        @JsonProperty("image_url")
        private ImageUrl imageUrl;

        /** 当 type="file" 时使用 */
        private FileContent file;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageUrl {
        /** 支持 https:// 外部链接 或 data:image/jpeg;base64,xxxx... Base64内联 */
        private String url;
        /** 可选："auto" | "low" | "high", 控制解析精度，默认 auto */
        private String detail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileContent {
        /** OpenAI 文件 ID，比如 "file-abc123" */
        @JsonProperty("file_id")
        private String fileId;
        /** 或者直接内嵌 base64 文件数据 */
        @JsonProperty("file_data")
        private String fileData;
        /** 文件名，可选 */
        private String filename;
    }
}
