package com.openrouter.adapter;

import com.openrouter.config.ChannelConfig;
import com.openrouter.model.ChatCompletionRequest;

import java.util.List;

public interface ModelRoutingStrategy {
    ChannelConfig selectChannel(ChatCompletionRequest request, List<ChannelConfig> availableChannels);
}
