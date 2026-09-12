package com.openrouter.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.List;

/**
 * 单个渠道（上游节点）的完整声明。
 * 持久化在 channels.json 的 channels 数组中；models 字段不落盘，
 * 由 ChannelConfigStore 在加载/保存后根据 models 数组的 channels 引用反向推导。
 */
@Data
public class ChannelConfig {

    // 全局唯一标识符
    private String id;

    // 此渠道要采用的翻译协议 (openai, gemini 等)
    private String type;

    // 节点的 API 地址 (不要保留尾部的 /v1)
    private String baseUrl;

    // 请求节点的凭证
    private String apiKey;

    // 基础打分权重，越大越优先
    private int baseWeight = 100;

    // 热插拔开关控制位
    private boolean enabled = true;

    // 运行时推导出的该渠道支持的模型名集合（不写入配置文件）
    @JsonIgnore
    private List<String> models = List.of();
}
