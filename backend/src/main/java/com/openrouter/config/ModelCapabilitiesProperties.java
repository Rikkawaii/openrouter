package com.openrouter.config;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型能力边界注册表（运行时视图）。
 * 不再从 yaml 绑定，由 {@link ChannelConfigStore} 在加载/替换 channels.json 时同步更新。
 * 路由策略在筛选 Channel 时依据此注册表过滤掉不具备所需能力的节点。
 */
@Data
@Component
public class ModelCapabilitiesProperties {

    /**
     * 全局默认能力（未在 models 中声明的型号会继承此默认值）
     */
    private ModelCapability defaults = new ModelCapability();

    /**
     * 各模型名称 -> 能力声明。Key 是模型 ID（与 RouterProperties.Channel.models 中一致）。
     */
    private Map<String, ModelCapability> models = new HashMap<>();

    /**
     * 获取某个模型的能力（如果未声明则回退到 defaults）
     */
    public ModelCapability getCapabilityForModel(String modelId) {
        return models.getOrDefault(modelId, defaults);
    }

    @Data
    public static class ModelCapability {
        /** 是否支持视觉识别（图片、图像分析） */
        private boolean vision = false;

        /** 是否支持 function calling / 工具调用 */
        private boolean functionCalling = false;

        /** 是否支持超长上下文（> 64K tokens） */
        private boolean longContext = false;
    }
}
