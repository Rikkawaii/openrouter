package com.openrouter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 映射 application.yaml 里面的通道列表配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openrouter")
public class RouterProperties {

    private List<Channel> channels;

    @Data
    public static class Channel {
        // 全局唯一标识符
        private String id;
        
        // 此渠道要采用的翻译协议 (openai, gemini 等)
        private String type;
        
        // 节点的 API 地址 (不要保留尾部的 /v1)
        private String baseUrl;
        
        // 请求节点的凭证
        private String apiKey;
        
        // 该渠道下支持的多个模型名集合 (例如 [gpt-4-turbo, deepseek-chat, claude-3])
        private List<String> models;
        
        // 基础打分权重，越大越优先
        private int baseWeight = 100;
        
        // ★ 手动启停开关控制位，后续可开放给 Dashboard 控制台持久化控制
        private boolean enabled = true;
    }
}
