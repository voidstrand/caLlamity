package com.llamafactory.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * Response from the OpenAI-compatible /v1/models endpoint.
 * Lists all models available on the Ollama instance.
 */
@Introspected
public record ModelsResponse(
        String object,
        List<Model> data
) {

    @Introspected
    public record Model(
            String id,
            String object,
            long created,
            @JsonProperty("owned_by") @Nullable String ownedBy
    ) {
    }
}
