package com.llamafactory.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * Response body from the OpenAI-compatible chat completions endpoint.
 */
@Introspected
public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        @Nullable Usage usage
) {

    @Introspected
    public record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") @Nullable String finishReason
    ) {
    }

    @Introspected
    public record Message(
            String role,
            String content
    ) {
    }

    @Introspected
    public record Usage(
            @JsonProperty("prompt_tokens") long promptTokens,
            @JsonProperty("completion_tokens") long completionTokens,
            @JsonProperty("total_tokens") long totalTokens
    ) {
    }

    /**
     * Extract the assistant's response content from the first choice.
     */
    public String getAssistantContent() {
        if (choices != null && !choices.isEmpty()) {
            return choices.getFirst().message().content();
        }
        return "";
    }
}
