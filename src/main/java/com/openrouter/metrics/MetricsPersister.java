package com.openrouter.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openrouter.config.RouterProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支持模型级细分账单的持久化处理器。
 */
@Slf4j
@Component
public class MetricsPersister {

    private final MetricsRegistry metricsRegistry;
    private final RouterProperties routerProperties;
    private final ObjectMapper objectMapper;
    private static final String STATE_FILE = ".openrouter-state.json";

    public MetricsPersister(MetricsRegistry metricsRegistry, RouterProperties routerProperties, ObjectMapper objectMapper) {
        this.metricsRegistry = metricsRegistry;
        this.routerProperties = routerProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void restoreState() {
        File file = new File(STATE_FILE);
        if (!file.exists()) return;
        try {
            Map<String, PersistedState> stateMap = objectMapper.readValue(file, new TypeReference<Map<String, PersistedState>>() {});
            List<RouterProperties.Channel> channels = routerProperties.getChannels();
            if (channels == null) return;

            for (RouterProperties.Channel channel : channels) {
                PersistedState savedState = stateMap.get(channel.getId());
                if (savedState != null) {
                    channel.setEnabled(savedState.isEnabled());
                    channel.setBaseWeight(savedState.getBaseWeight());

                    ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
                    
                    // 1. 恢复全量指标
                    if (savedState.getPromptTokensUsed() != null && savedState.getCompletionTokensUsed() != null) {
                        metrics.addTokens(savedState.getPromptTokensUsed(), savedState.getCompletionTokensUsed());
                    }
                    
                    if (savedState.getTotalCalls() != null) {
                        for (int i = 0; i < savedState.getTotalCalls(); i++) metrics.recordCall();
                    }
                    if (savedState.getErrorCount() != null) {
                        for (int i = 0; i < savedState.getErrorCount(); i++) metrics.recordError();
                    }
                    
                    // 2. 恢复分模型明细账单
                    Map<String, ModelMetrics.TokenPairView> savedTokensByModel = savedState.getTokensByModel();
                    if (savedTokensByModel != null) {
                        savedTokensByModel.forEach((model, pair) -> {
                            metrics.addTokens(model, pair.getP(), pair.getC());
                        });
                    }
                }
            }
            log.info("✅ 已从磁盘载入精细化分模型账单！");
        } catch (Exception e) {
            log.error("恢复持久化状态失败", e);
        }
    }

    @Scheduled(fixedRate = 10000)
    public void flushStateToDisk() {
        List<RouterProperties.Channel> channels = routerProperties.getChannels();
        if (channels == null) return;

        Map<String, PersistedState> stateSnapshot = new HashMap<>();
        for (RouterProperties.Channel channel : channels) {
            ModelMetrics metrics = metricsRegistry.getMetrics(channel.getId());
            PersistedState state = new PersistedState();
            state.setEnabled(channel.isEnabled());
            state.setBaseWeight(channel.getBaseWeight());
            state.setTotalTokensUsed(metrics.getTotalTokensUsed());
            state.setPromptTokensUsed(metrics.getPromptTokensUsed());
            state.setCompletionTokensUsed(metrics.getCompletionTokensUsed());
            state.setTotalCalls(metrics.getTotalCalls());
            state.setErrorCount(metrics.getErrorCount());
            state.setTokensByModel(metrics.getTokensByModel());
            stateSnapshot.put(channel.getId(), state);
        }

        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(STATE_FILE), stateSnapshot);
        } catch (IOException e) {
            log.error("落盘失败", e);
        }
    }

    @Data
    public static class PersistedState {
        private boolean enabled;
        private int baseWeight;
        private Long totalTokensUsed;
        private Long promptTokensUsed;
        private Long completionTokensUsed;
        private Long totalCalls;
        private Long errorCount;
        private Map<String, ModelMetrics.TokenPairView> tokensByModel;
    }
}
