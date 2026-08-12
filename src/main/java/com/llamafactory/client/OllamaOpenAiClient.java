package com.llamafactory.client;

import com.llamafactory.client.dto.ChatCompletionRequest;
import com.llamafactory.client.dto.ChatCompletionResponse;
import com.llamafactory.client.dto.ModelsResponse;
import com.llamafactory.client.dto.OllamaProcessResponse;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Non-blocking reactive HTTP client for communicating with Ollama.
 * Uses Project Reactor (Mono) so Netty event loops are never blocked.
 */
@Singleton
public class OllamaOpenAiClient {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaOpenAiClient.class);

    private final HttpClient httpClient;
    private final String apiKey;

    @Inject
    public OllamaOpenAiClient(
            @Client("${ollama.base-url}") HttpClient httpClient,
            @Value("${ollama.api-key:ollama}") String apiKey
    ) {
        this.httpClient = httpClient;
        this.apiKey = apiKey;
    }

    /**
     * Non-blocking chat completion request using OpenAI-compatible endpoint.
     */
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        LOG.debug("Sending async chat completion request for model '{}' with {} messages",
                request.model(), request.messages().size());

        HttpRequest<ChatCompletionRequest> httpRequest = HttpRequest
                .POST("/v1/chat/completions", request)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");

        return Mono.from(httpClient.retrieve(httpRequest, ChatCompletionResponse.class))
                .doOnNext(response -> LOG.debug("Received async response: {} total tokens",
                        response.usage() != null ? response.usage().totalTokens() : "unknown"));
    }

    /**
     * Non-blocking list of available models.
     */
    public Mono<ModelsResponse> listModels() {
        LOG.debug("Listing available models via /v1/models asynchronously");

        HttpRequest<?> request = HttpRequest.GET("/v1/models")
                .header("Authorization", "Bearer " + apiKey);

        return Mono.from(httpClient.retrieve(request, ModelsResponse.class));
    }

    /**
     * Non-blocking query of running processes via native /api/ps.
     */
    public Mono<OllamaProcessResponse> listRunningProcesses() {
        LOG.debug("Querying running processes via /api/ps asynchronously");

        HttpRequest<?> request = HttpRequest.GET("/api/ps");

        return Mono.from(httpClient.retrieve(request, OllamaProcessResponse.class))
                .onErrorReturn(new OllamaProcessResponse(java.util.Collections.emptyList()));
    }
}
