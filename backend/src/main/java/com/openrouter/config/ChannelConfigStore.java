package com.openrouter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渠道/模型配置的唯一事实源（channels.json）。
 * <p>
 * 文件结构是平铺的两个数组：channels 只声明节点本身（不含模型），
 * models 通过 channels 引用列表与节点多对多关联；加载时反向推导每个
 * 渠道的 models 集合并把能力声明同步进 {@link ModelCapabilitiesProperties}。
 * <p>
 * 写入统一走「临时文件 + ATOMIC_MOVE」，保证任何时刻磁盘上的文件都是完整可解析的。
 */
@Slf4j
@Component
public class ChannelConfigStore {

    private final ObjectMapper objectMapper;
    private final ModelCapabilitiesProperties capabilitiesProperties;
    private final RouterProperties routerProperties;

    private final Path filePath;
    private final String seedResource;

    /** 当前生效的配置（内存中的对象可直接被 toggle/weight 原地修改后 persist） */
    private volatile ConfigFile state = new ConfigFile();

    public ChannelConfigStore(ObjectMapper objectMapper,
                              ModelCapabilitiesProperties capabilitiesProperties,
                              RouterProperties routerProperties,
                              @Value("${openrouter.config-file:backend/config/channels.json}") String filePath,
                              @Value("${openrouter.config-seed:seed-channels.json}") String seedResource) {
        // copy() 出私有实例，避免美化输出等设置影响全局共享的 ObjectMapper
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.capabilitiesProperties = capabilitiesProperties;
        this.routerProperties = routerProperties;
        this.filePath = Path.of(filePath);
        this.seedResource = seedResource;
    }

    /** 网关级基础设置，随 channels.json 一起持久化；运行时回填到 RouterProperties */
    @Data
    public static class Settings {
        // 是否启用网关 API Key 鉴权（false = 无需 key）
        private Boolean apiKeyEnabled = true;
        // 网关鉴权密钥（/v1/** 接口）
        private String apiKey;
        // 管理页面登录密码
        private String adminPassword;
        // 导师规则：全新会话（无上下文）时强制优先指派的模型，空为关闭
        private String mentorModel;
        // 路由打分参数；为 null 表示尚未在管理页设置过，沿用 application.yaml 的引导值
        private RoutingConfig routing;
    }

    /** 配置文件的顶层结构，与磁盘上的 channels.json 一一对应 */
    @Data
    public static class ConfigFile {
        private Settings settings = new Settings();
        private List<ChannelConfig> channels = new ArrayList<>();
        private List<ModelEntry> models = new ArrayList<>();
    }

    @PostConstruct
    public void load() throws IOException {
        if (!Files.exists(filePath)) {
            Files.createDirectories(filePath.getParent());
            Files.copy(new ClassPathResource(seedResource).getInputStream(), filePath);
            log.info("📄 配置文件不存在，已从内置模板生成: {}", filePath.toAbsolutePath());
        }
        ConfigFile parsed = objectMapper.readValue(filePath.toFile(), ConfigFile.class);
        if (parsed.getSettings() == null) {
            parsed.setSettings(new Settings());
        }
        // 加载与替换走同一条校验/推导路径，保证启动态和运行态一致
        replaceAll(parsed.getChannels(), parsed.getModels(), parsed.getSettings());
        log.info("✅ 配置加载完成: {} 个渠道, {} 个模型 ({})", state.getChannels().size(),
                state.getModels().size(), filePath.toAbsolutePath());
    }

    // ==================== 基础设置的有效值视图 ====================
    // settings 段的字段为 null 表示"尚未在管理页设置过"，此时沿用 application.yaml 的引导值；
    // 不能在启动迁移时直接拷贝 RouterProperties，因为 @ConfigurationProperties 绑定晚于本组件初始化。

    public String effectiveApiKey() {
        String v = state.getSettings().getApiKey();
        return v != null ? v : routerProperties.getApiKey();
    }

    public String effectiveAdminPassword() {
        String v = state.getSettings().getAdminPassword();
        return v != null ? v : routerProperties.getAdminPassword();
    }

    public String effectiveMentorModel() {
        String v = state.getSettings().getMentorModel();
        return v != null ? v : routerProperties.getMentorModel();
    }

    /**
     * 生效的路由打分参数：settings 段未设置时沿用 application.yaml 的引导值。
     * 保证永不返回 null，调用方可直接使用。
     */
    public RoutingConfig effectiveRouting() {
        RoutingConfig v = state.getSettings().getRouting();
        if (v != null) return v;
        RoutingConfig bootstrap = routerProperties.getRouting();
        return bootstrap != null ? bootstrap : new RoutingConfig();
    }

    public List<ChannelConfig> getChannels() {
        return state.getChannels();
    }

    public List<ModelEntry> getModels() {
        return state.getModels();
    }

    public ConfigFile snapshot() {
        return state;
    }

    /**
     * 全量替换配置：校验 -> 原子落盘 -> 切换内存状态。
     * 任一校验失败抛出 IllegalArgumentException，磁盘与内存都不受影响。
     * settings 为 null 时保留现有基础设置。
     */
    public synchronized void replaceAll(List<ChannelConfig> channels, List<ModelEntry> models,
            Settings settings) throws IOException {
        validate(channels, models);
        if (settings == null) {
            settings = state.getSettings();
        }

        ConfigFile next = new ConfigFile();
        next.setSettings(settings);
        next.setChannels(copyChannels(channels));
        next.setModels(copyModels(models));

        persist(next);
        this.state = next;
        applyDerivedState(next);
    }

    /**
     * 更新基础设置（网关 Key / 登录密码 / 导师模型），其余配置保持不变。
     * 传入前应已完成掩码解析与校验。
     */
    public synchronized void updateSettings(Settings settings) throws IOException {
        state.getSettings().setApiKeyEnabled(settings.getApiKeyEnabled());
        if (settings.getApiKey() != null) state.getSettings().setApiKey(settings.getApiKey());
        if (settings.getAdminPassword() != null) state.getSettings().setAdminPassword(settings.getAdminPassword());
        if (settings.getMentorModel() != null) state.getSettings().setMentorModel(settings.getMentorModel());
        if (settings.getRouting() != null) state.getSettings().setRouting(settings.getRouting().copy());
        persist(state);
        applySettings(state.getSettings());
    }

    /** 切换渠道启停（原地修改当前状态）并持久化 */
    public synchronized boolean toggleChannel(String id) throws IOException {
        ChannelConfig channel = findById(state.getChannels(), id);
        if (channel == null) return false;
        channel.setEnabled(!channel.isEnabled());
        persist(state);
        return true;
    }

    /** 调整渠道权重并持久化 */
    public synchronized boolean setWeight(String id, int weight) throws IOException {
        ChannelConfig channel = findById(state.getChannels(), id);
        if (channel == null) return false;
        channel.setBaseWeight(weight);
        persist(state);
        return true;
    }

    // ==================== 内部实现 ====================

    private void validate(List<ChannelConfig> channels, List<ModelEntry> models) {
        if (channels == null || models == null) {
            throw new IllegalArgumentException("channels 与 models 数组均不能为空");
        }

        Set<String> channelIds = new HashSet<>();
        for (ChannelConfig ch : channels) {
            if (ch.getId() == null || !ch.getId().matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("渠道 ID 只能包含字母、数字、下划线和连字符: " + ch.getId());
            }
            if (!channelIds.add(ch.getId())) {
                throw new IllegalArgumentException("渠道 ID 重复: " + ch.getId());
            }
            if (ch.getType() == null || ch.getType().isBlank()) {
                throw new IllegalArgumentException("渠道 " + ch.getId() + " 缺少协议类型 (type)");
            }
            if (ch.getBaseUrl() == null || ch.getBaseUrl().isBlank()) {
                throw new IllegalArgumentException("渠道 " + ch.getId() + " 缺少 Base URL");
            }
            if (ch.getApiKey() == null || ch.getApiKey().isBlank()) {
                throw new IllegalArgumentException("渠道 " + ch.getId() + " 缺少 API Key");
            }
            if (ch.getBaseWeight() < 0) {
                throw new IllegalArgumentException("渠道 " + ch.getId() + " 的权重不能为负数");
            }
        }

        Set<String> modelNames = new HashSet<>();
        for (ModelEntry m : models) {
            if (m.getName() == null || m.getName().isBlank()) {
                throw new IllegalArgumentException("存在未命名模型");
            }
            if (!modelNames.add(m.getName())) {
                throw new IllegalArgumentException("模型名重复: " + m.getName());
            }
            if (m.getChannels() == null) m.setChannels(new ArrayList<>());
            for (String cid : m.getChannels()) {
                if (!channelIds.contains(cid)) {
                    throw new IllegalArgumentException("模型 " + m.getName() + " 引用了不存在的渠道: " + cid);
                }
            }
            if (m.getCapabilities() == null) {
                m.setCapabilities(new ModelCapabilitiesProperties.ModelCapability());
            }
        }
    }

    /** 深拷贝，避免外部传入的对象后续被改导致内存态与磁盘态不一致 */
    private List<ChannelConfig> copyChannels(List<ChannelConfig> channels) throws IOException {
        List<ChannelConfig> copies = new ArrayList<>(channels.size());
        for (ChannelConfig ch : channels) {
            copies.add(objectMapper.readValue(objectMapper.writeValueAsString(ch), ChannelConfig.class));
        }
        return copies;
    }

    private List<ModelEntry> copyModels(List<ModelEntry> models) throws IOException {
        List<ModelEntry> copies = new ArrayList<>(models.size());
        for (ModelEntry m : models) {
            copies.add(objectMapper.readValue(objectMapper.writeValueAsString(m), ModelEntry.class));
        }
        return copies;
    }

    private void persist(ConfigFile config) throws IOException {
        String json = objectMapper.writeValueAsString(config);
        Path tmp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 根据模型的 channels 引用反向推导每个渠道支持的模型集合，
     * 并把能力声明同步进 ModelCapabilitiesProperties 供路由策略消费。
     */
    private void applyDerivedState(ConfigFile config) {
        Map<String, List<String>> modelsByChannel = new LinkedHashMap<>();
        for (ModelEntry m : config.getModels()) {
            for (String cid : m.getChannels()) {
                modelsByChannel.computeIfAbsent(cid, k -> new ArrayList<>()).add(m.getName());
            }
        }
        for (ChannelConfig ch : config.getChannels()) {
            ch.setModels(modelsByChannel.getOrDefault(ch.getId(), List.of()));
        }

        Map<String, ModelCapabilitiesProperties.ModelCapability> caps = new LinkedHashMap<>();
        for (ModelEntry m : config.getModels()) {
            caps.put(m.getName(), m.getCapabilities());
        }
        capabilitiesProperties.setModels(caps);

        applySettings(config.getSettings());
    }

    /** 把基础设置回填到 RouterProperties，鉴权过滤器与登录逻辑即时生效 */
    private void applySettings(Settings s) {
        if (s == null) return;
        if (s.getApiKeyEnabled() != null) routerProperties.setApiKeyEnabled(s.getApiKeyEnabled());
        if (s.getApiKey() != null) routerProperties.setApiKey(s.getApiKey());
        if (s.getAdminPassword() != null) routerProperties.setAdminPassword(s.getAdminPassword());
        if (s.getMentorModel() != null) routerProperties.setMentorModel(s.getMentorModel());
        if (s.getRouting() != null) routerProperties.setRouting(s.getRouting().copy());
    }

    private ChannelConfig findById(List<ChannelConfig> channels, String id) {
        return channels.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
