package com.llamafactory.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Request body for the OpenAI-compatible chat completions endpoint.
 * Maps to POST /v1/chat/completions on Ollama.
 */
@Introspected
public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        @Nullable Double temperature,
        @JsonProperty("max_tokens") @Nullable Integer maxTokens,
        @JsonProperty("top_p") @Nullable Double topP
) {

    @Introspected
    public record Message(
            String role,
            String content
    ) {
    }

    /**
     * Build a request from instance parameters and conversation history.
     */
    public static ChatCompletionRequest from(
            String model,
            List<com.llamafactory.model.ChatMessage> history,
            double temperature,
            int maxTokens,
            double topP
    ) {
        List<Message> messages = history.stream()
                .map(m -> new Message(m.role(), m.content()))
                .toList();
        return new ChatCompletionRequest(model, messages, temperature, maxTokens, topP);
    }
}
