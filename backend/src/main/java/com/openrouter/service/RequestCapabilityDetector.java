package com.openrouter.service;

import com.openrouter.model.ChatCompletionMessage;
import com.openrouter.model.ChatCompletionRequest;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 请求能力需求探测器。
 * 在路由前静默扫描请求体，提炼出该次调用实际需要的模型能力集合。
 * 这样路由策略可以在打分前就过滤掉不满足能力要求的渠道节点。
 */
@Component
public class RequestCapabilityDetector {

    /**
     * 分析请求需要的能力
     */
    public RequiredCapabilities detect(ChatCompletionRequest request) {
        boolean needsVision = false;

        if (request.getMessages() != null) {
            for (ChatCompletionMessage msg : request.getMessages()) {
                Object content = msg.getContent();
                if (content instanceof List) {
                    // 多模态内容数组：扫描各个 part 的 type
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content;
                    for (Map<String, Object> part : parts) {
                        String type = (String) part.get("type");
                        if ("image_url".equals(type) || "file".equals(type) || "input_audio".equals(type)) {
                            needsVision = true;
                            break;
                        }
                    }
                }
                if (needsVision) break;
            }
        }

        return new RequiredCapabilities(needsVision);
    }

    @Getter
    public static class RequiredCapabilities {
        private final boolean vision;

        public RequiredCapabilities(boolean vision) {
            this.vision = vision;
        }

        public boolean isTextOnly() {
            return !vision;
        }

        @Override
        public String toString() {
            return "RequiredCapabilities{vision=" + vision + "}";
        }
    }
}
