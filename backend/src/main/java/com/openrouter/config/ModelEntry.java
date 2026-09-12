package com.openrouter.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个模型的声明：归属渠道 + 能力边界。
 * 持久化在 channels.json 的 models 数组中，与渠道通过 channels 引用列表多对多关联。
 */
@Data
public class ModelEntry {

    // 模型 ID（对上游与请求方暴露的名称，全局唯一）
    private String name;

    // 支持该模型的渠道 id 列表
    private List<String> channels = new ArrayList<>();

    // 能力边界声明（未声明的项默认 false）
    private ModelCapabilitiesProperties.ModelCapability capabilities = new ModelCapabilitiesProperties.ModelCapability();
}
