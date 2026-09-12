package com.openrouter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 映射 application.yaml 里的网关级配置。
 * 渠道/模型/基础设置运行时可变部分由 {@link ChannelConfigStore} 加载 channels.json 后回填覆盖。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openrouter")
public class RouterProperties {

    // 路由网关对外的统一授权密钥
    private String apiKey;

    // 是否启用 API Key 鉴权（false = 无需 key，公开访问）
    private boolean apiKeyEnabled = true;

    // 管理页面登录密码
    private String adminPassword;

    // 导师规则：无上下文时优先指派的模型
    private String mentorModel;
}
