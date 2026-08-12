package com.llamafactory.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llamafactory.client.OllamaOpenAiClient;
import com.llamafactory.model.ChatMessage;
import com.llamafactory.model.LlmInstance;
import com.llamafactory.service.InstanceManager;
import com.llamafactory.service.MetricsService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;

/**
 * Non-blocking JSON-RPC 2.0 message dispatcher for MCP.
 * Supports autonomous backchannel peer collaboration tools.
 */
@Singleton
public class McpMessageDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(McpMessageDispatcher.class);

    private final InstanceManager instanceManager;
    private final MetricsService metricsService;
    private final OllamaOpenAiClient ollamaClient;
    private final ObjectMapper objectMapper;

    @Inject
    public McpMessageDispatcher(
            InstanceManager instanceManager,
            MetricsService metricsService,
            OllamaOpenAiClient ollamaClient,
            ObjectMapper objectMapper
    ) {
        this.instanceManager = instanceManager;
        this.metricsService = metricsService;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    public Mono<String> dispatch(String requestJson) {
        try {
            JsonNode root = objectMapper.readTree(requestJson);
            if (!root.has("method")) {
                return Mono.just(buildErrorResponse(root.has("id") ? root.get("id") : null, -32600, "Invalid Request: missing method"));
            }

            String method = root.get("method").asText();
            JsonNode idNode = root.get("id");

            return switch (method) {
                case "initialize" -> Mono.just(buildInitializeResponse(idNode));
                case "tools/list" -> Mono.just(buildToolsListResponse(idNode));
                case "tools/call" -> handleToolCall(root, idNode);
                case "resources/list" -> Mono.just(buildEmptyListResponse(idNode, "resources"));
                case "prompts/list" -> Mono.just(buildEmptyListResponse(idNode, "prompts"));
                default -> Mono.just(buildErrorResponse(idNode, -32601, "Method not found: " + method));
            };
        } catch (Exception e) {
            LOG.error("Error parsing MCP request: {}", e.getMessage());
            return Mono.just(buildErrorResponse(null, -32700, "Parse error: " + e.getMessage()));
        }
    }

    private String buildInitializeResponse(JsonNode id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.set("tools", objectMapper.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", "caLlamity");
        serverInfo.put("version", "1.1.0");
        result.set("serverInfo", serverInfo);
        response.set("result", result);
        return response.toString();
    }

    private String buildEmptyListResponse(JsonNode id, String key) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode result = response.putObject("result");
        result.putArray(key);
        return response.toString();
    }

    private String buildToolsListResponse(JsonNode id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        addTool(tools, "list_available_models", "List all models available on the connected Ollama instance.", null);
        addTool(tools, "create_instance", "Spawn a new LLM instance with a model name, alias, temperature, max_tokens, and top_p.", List.of("modelName", "alias"));
        addTool(tools, "list_instances", "List all currently active spawned LLM instances.", null);
        addTool(tools, "get_instance_detail", "Get full details, configuration, status, and metrics for a specific instance.", List.of("alias"));
        addTool(tools, "terminate_instance", "Terminate an instance by alias or UUID.", List.of("alias"));
        addTool(tools, "send_message", "Send a message to an LLM instance and receive its response. Context is preserved.", List.of("alias", "message"));
        addTool(tools, "assign_task", "Assign a task description to an instance and get its initial response.", List.of("alias", "taskDescription"));
        addTool(tools, "get_conversation_history", "Get the conversational history for a given instance.", List.of("alias"));
        addTool(tools, "send_message_to_instance", "Route a message from one instance to another.", List.of("fromAlias", "toAlias", "message"));
        addTool(tools, "broadcast_message", "Broadcast a message to multiple instances (or 'all').", List.of("message"));
        addTool(tools, "start_peer_collaboration", "Start an autonomous backchannel collaboration between two instances directly on the server. Relays multi-turn messages locally without burning client/agent tokens.", List.of("instanceA", "instanceB", "taskPrompt"));
        addTool(tools, "get_metrics", "Get active instance metrics, task summaries, token counts, and memory/VRAM usage.", null);

        response.set("result", result);
        return response.toString();
    }

    private void addTool(ArrayNode tools, String name, String description, List<String> requiredProps) {
        ObjectNode t = tools.addObject();
        t.put("name", name);
        t.put("description", description);
        ObjectNode s = t.putObject("inputSchema");
        s.put("type", "object");
        s.putObject("properties");
        if (requiredProps != null && !requiredProps.isEmpty()) {
            ArrayNode req = s.putArray("required");
            requiredProps.forEach(req::add);
        }
    }

    private Mono<String> handleToolCall(JsonNode root, JsonNode id) {
        if (!root.has("params") || !root.get("params").has("name")) {
            return Mono.just(buildErrorResponse(id, -32602, "Invalid params: tool name missing"));
        }

        String toolName = root.get("params").get("name").asText();
        JsonNode args = root.get("params").get("arguments");

        Mono<String> textResultMono = switch (toolName) {
            case "list_available_models" -> handleListModels();
            case "create_instance" -> Mono.just(handleCreateInstance(args));
            case "list_instances" -> Mono.just(handleListInstances());
            case "get_instance_detail" -> Mono.just(handleGetInstanceDetail(args));
            case "terminate_instance" -> Mono.just(handleTerminateInstance(args));
            case "send_message" -> handleSendMessage(args);
            case "assign_task" -> handleAssignTask(args);
            case "get_conversation_history" -> Mono.just(handleGetConversationHistory(args));
            case "send_message_to_instance" -> handleSendMessageToInstance(args);
            case "broadcast_message" -> handleBroadcastMessage(args);
            case "start_peer_collaboration" -> handleStartPeerCollaboration(args);
            case "get_metrics" -> metricsService.generateMetricsReport();
            default -> Mono.error(new IllegalArgumentException("Unknown tool: " + toolName));
        };

        return textResultMono
                .map(text -> buildToolSuccessResponse(id, text))
                .onErrorResume(e -> Mono.just(buildErrorResponse(id, -32603, "Tool execution error: " + e.getMessage())));
    }

    private String buildToolSuccessResponse(JsonNode id, String textResult) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode result = response.putObject("result");
        ArrayNode contentNode = result.putArray("content");
        ObjectNode textContent = contentNode.addObject();
        textContent.put("type", "text");
        textContent.put("text", textResult);
        return response.toString();
    }

    private Mono<String> handleListModels() {
        return ollamaClient.listModels()
                .map(response -> {
                    if (response.data() == null || response.data().isEmpty()) {
                        return "No models available on Ollama instance.";
                    }
                    StringBuilder sb = new StringBuilder("Available Models:\n");
                    response.data().forEach(m -> sb.append(" • ").append(m.id()).append("\n"));
                    return sb.toString();
                })
                .onErrorReturn("Error listing models.");
    }

    private String handleCreateInstance(JsonNode args) {
        if (args == null || !args.has("modelName") || !args.has("alias")) {
            return "Error: 'modelName' and 'alias' are required.";
        }
        String modelName = args.get("modelName").asText();
        String alias = args.get("alias").asText();
        double temp = args.has("temperature") ? args.get("temperature").asDouble() : 0.7;
        int maxTokens = args.has("maxTokens") ? args.get("maxTokens").asInt() : 2048;
        double topP = args.has("topP") ? args.get("topP").asDouble() : 0.9;

        LlmInstance instance = instanceManager.createInstance(modelName, alias, temp, maxTokens, topP);
        return String.format("✓ Created instance '%s' (ID: %s, Model: %s)", instance.getAlias(), instance.getId(), instance.getModelName());
    }

    private String handleListInstances() {
        Collection<LlmInstance> active = instanceManager.getAllActiveInstances();
        if (active.isEmpty()) return "No active instances currently spawned.";
        StringBuilder sb = new StringBuilder("Active Instances (" + active.size() + "):\n\n");
        active.forEach(i -> sb.append(String.format("- Alias: %s | Model: %s | Status: %s | Tokens: %d\n", i.getAlias(), i.getModelName(), i.getStatus(), i.getTotalTokensUsed())));
        return sb.toString();
    }

    private String handleGetInstanceDetail(JsonNode args) {
        if (args == null || !args.has("alias")) return "Error: 'alias' is required.";
        LlmInstance instance = instanceManager.requireInstance(args.get("alias").asText());
        return String.format("Instance: %s | Model: %s | Status: %s | Tokens: %d | Msgs: %d",
                instance.getAlias(), instance.getModelName(), instance.getStatus(), instance.getTotalTokensUsed(), instance.getConversationLength());
    }

    private String handleTerminateInstance(JsonNode args) {
        if (args == null || !args.has("alias")) return "Error: 'alias' is required.";
        instanceManager.terminateInstance(args.get("alias").asText());
        return "✓ Instance terminated.";
    }

    private Mono<String> handleSendMessage(JsonNode args) {
        if (args == null || !args.has("alias") || !args.has("message")) {
            return Mono.just("Error: 'alias' and 'message' are required.");
        }
        String alias = args.get("alias").asText();
        return instanceManager.sendMessage(alias, args.get("message").asText())
                .map(resp -> "(" + alias + "): " + resp);
    }

    private Mono<String> handleAssignTask(JsonNode args) {
        if (args == null || !args.has("alias") || !args.has("taskDescription")) {
            return Mono.just("Error: 'alias' and 'taskDescription' are required.");
        }
        String alias = args.get("alias").asText();
        return instanceManager.assignTask(alias, args.get("taskDescription").asText())
                .map(resp -> "Task assigned to '" + alias + "'. Response:\n" + resp);
    }

    private String handleGetConversationHistory(JsonNode args) {
        if (args == null || !args.has("alias")) return "Error: 'alias' is required.";
        String alias = args.get("alias").asText();
        LlmInstance instance = instanceManager.requireInstance(alias);
        List<ChatMessage> history = instance.getConversationHistory();
        StringBuilder sb = new StringBuilder("History for '" + alias + "':\n");
        history.forEach(m -> sb.append("[").append(m.role().toUpperCase()).append("]: ").append(m.content()).append("\n"));
        return sb.toString();
    }

    private Mono<String> handleSendMessageToInstance(JsonNode args) {
        if (args == null || !args.has("fromAlias") || !args.has("toAlias") || !args.has("message")) {
            return Mono.just("Error: required params missing.");
        }
        return instanceManager.routeMessage(args.get("fromAlias").asText(), args.get("toAlias").asText(), args.get("message").asText());
    }

    private Mono<String> handleBroadcastMessage(JsonNode args) {
        if (args == null || !args.has("message")) return Mono.just("Error: 'message' is required.");
        String msg = args.get("message").asText();
        String aliasesStr = args.has("aliases") ? args.get("aliases").asText() : "all";
        List<String> list = "all".equalsIgnoreCase(aliasesStr) ? null : List.of(aliasesStr.split("\\s*,\\s*"));
        return instanceManager.broadcastMessage(list, msg)
                .map(res -> "Broadcast Results:\n" + res.toString());
    }

    private Mono<String> handleStartPeerCollaboration(JsonNode args) {
        if (args == null || !args.has("instanceA") || !args.has("instanceB") || !args.has("taskPrompt")) {
            return Mono.just("Error: 'instanceA', 'instanceB', and 'taskPrompt' are required.");
        }
        String a = args.get("instanceA").asText();
        String b = args.get("instanceB").asText();
        String prompt = args.get("taskPrompt").asText();
        int turns = args.has("maxTurns") ? args.get("maxTurns").asInt() : 6;

        return instanceManager.runAutonomousCollaboration(a, b, prompt, turns);
    }

    private String buildErrorResponse(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response.toString();
    }
}
