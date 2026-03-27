package com.openrouter.adapter;

import com.openrouter.config.RouterProperties;
import com.openrouter.model.ChatCompletionRequest;

import java.util.List;

public interface ModelRoutingStrategy {
    RouterProperties.Channel selectChannel(ChatCompletionRequest request, List<RouterProperties.Channel> availableChannels);
}
