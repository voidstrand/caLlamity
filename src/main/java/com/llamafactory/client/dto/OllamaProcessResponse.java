package com.llamafactory.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * Response from the native Ollama /api/ps endpoint.
 * Shows currently running models with memory allocation details.
 * This uses the native Ollama API (not OpenAI-compat) because
 * memory/process info is not available via the OpenAI endpoints.
 */
@Introspected
public record OllamaProcessResponse(
        List<RunningModel> models
) {

    @Introspected
    public record RunningModel(
            String name,
            String model,
            long size,
            @JsonProperty("size_vram") long sizeVram,
            String digest,
            @Nullable Details details,
            @JsonProperty("expires_at") @Nullable String expiresAt
    ) {
    }

    @Introspected
    public record Details(
            @JsonProperty("parent_model") @Nullable String parentModel,
            String format,
            String family,
            @JsonProperty("parameter_size") @Nullable String parameterSize,
            @JsonProperty("quantization_level") @Nullable String quantizationLevel
    ) {
    }
}
