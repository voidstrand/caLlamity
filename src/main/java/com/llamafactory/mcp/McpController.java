package com.llamafactory.mcp;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.sse.Event;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Truly non-blocking Controller serving MCP over Streamable HTTP and SSE.
 * Operates natively on Netty event loops without blocking threads.
 */
@Controller
public class McpController {

    private static final Logger LOG = LoggerFactory.getLogger(McpController.class);

    private final McpMessageDispatcher dispatcher;
    private final Map<String, FluxSink<Event<String>>> sseSessions = new ConcurrentHashMap<>();

    public McpController(McpMessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Get(value = "/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> establishSsePrimary() {
        return establishSseInternal();
    }

    @Get(value = "/api/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> establishSseApi() {
        return establishSseInternal();
    }

    private Publisher<Event<String>> establishSseInternal() {
        return Flux.create(sink -> {
            String connectionId = UUID.randomUUID().toString();
            LOG.info("New MCP SSE Connection established. ID: {}", connectionId);

            sseSessions.put(connectionId, sink);

            sink.onDispose(() -> {
                LOG.info("MCP SSE Connection closed. ID: {}", connectionId);
                sseSessions.remove(connectionId);
            });

            String endpointUrl = "/mcp/message?connection_id=" + connectionId;
            sink.next(Event.of(endpointUrl).name("endpoint"));
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    @Post(value = "/mcp/message", consumes = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<?>> receiveMessagePrimary(
            @QueryValue("connection_id") String connectionId,
            @Body String body) {
        return receiveMessageInternal(connectionId, body);
    }

    @Post(value = "/api/mcp/message", consumes = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<?>> receiveMessageApi(
            @QueryValue("connection_id") String connectionId,
            @Body String body) {
        return receiveMessageInternal(connectionId, body);
    }

    private Mono<HttpResponse<?>> receiveMessageInternal(String connectionId, String body) {
        LOG.debug("Received MCP message for connection {}: {}", connectionId, body);

        FluxSink<Event<String>> sink = sseSessions.get(connectionId);
        if (sink == null) {
            LOG.warn("No active SSE session found for connection_id: {}", connectionId);
            return Mono.just(HttpResponse.badRequest("Connection session not found"));
        }

        return dispatcher.dispatch(body)
                .map(responseJson -> {
                    sink.next(Event.of(responseJson).name("message"));
                    return HttpResponse.accepted();
                });
    }

    @Post(value = "/mcp", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public Mono<String> handleHttpMcpPrimary(@Body String body) {
        LOG.debug("Received HTTP MCP request: {}", body);
        return dispatcher.dispatch(body);
    }

    @Post(value = "/api/mcp", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public Mono<String> handleHttpMcpApi(@Body String body) {
        LOG.debug("Received HTTP MCP request: {}", body);
        return dispatcher.dispatch(body);
    }
}
