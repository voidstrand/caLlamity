package com.llamafactory.service;

import com.llamafactory.client.OllamaOpenAiClient;
import com.llamafactory.client.dto.ChatCompletionRequest;
import com.llamafactory.model.ChatMessage;
import com.llamafactory.model.InstanceStatus;
import com.llamafactory.model.LlmInstance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle and state of all LLM instances spawned by the factory.
 * Includes an autonomous backchannel loop for server-side peer-to-peer collaboration
 * with individual per-party token consumption tracking.
 */
@Singleton
public class InstanceManager {

    private static final Logger LOG = LoggerFactory.getLogger(InstanceManager.class);

    private final ConcurrentHashMap<String, LlmInstance> instances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idToAlias = new ConcurrentHashMap<>();
    private final OllamaOpenAiClient ollamaClient;

    @Inject
    public InstanceManager(OllamaOpenAiClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public LlmInstance createInstance(String modelName, String alias, double temperature, int maxTokens, double topP) {
        if (instances.containsKey(alias)) {
            throw new IllegalArgumentException("An instance with alias '" + alias + "' already exists.");
        }

        LlmInstance instance = new LlmInstance(alias, modelName, temperature, maxTokens, topP);
        instance.addMessage(ChatMessage.system(
                "You are an AI assistant instance managed by the OllamaFactory. " +
                "Your alias is '" + alias + "' and you are running on model '" + modelName + "'. " +
                "You may be assigned tasks, asked questions, or asked to collaborate with other instances. " +
                "When collaborating with another instance, provide ONLY your own perspective. Do NOT write dialogue or responses for the other instance. " +
                "When both of you reach a final consensus, include '[DONE]' at the end of your response."
        ));

        instances.put(alias, instance);
        idToAlias.put(instance.getId(), alias);

        LOG.info("Created instance '{}' (id={}) using model '{}'", alias, instance.getId(), modelName);
        return instance;
    }

    public LlmInstance getInstance(String aliasOrId) {
        LlmInstance instance = instances.get(aliasOrId);
        if (instance != null) return instance;
        String alias = idToAlias.get(aliasOrId);
        return alias != null ? instances.get(alias) : null;
    }

    public LlmInstance requireInstance(String aliasOrId) {
        LlmInstance instance = getInstance(aliasOrId);
        if (instance == null) {
            throw new IllegalArgumentException("No instance found with alias or ID: '" + aliasOrId + "'");
        }
        if (instance.getStatus() == InstanceStatus.TERMINATED) {
            throw new IllegalArgumentException("Instance '" + aliasOrId + "' has been terminated.");
        }
        return instance;
    }

    public Collection<LlmInstance> getAllActiveInstances() {
        return instances.values().stream()
                .filter(i -> i.getStatus() != InstanceStatus.TERMINATED)
                .toList();
    }

    public Mono<String> sendMessage(String aliasOrId, String userMessage) {
        LlmInstance instance;
        try {
            instance = requireInstance(aliasOrId);
        } catch (Exception e) {
            return Mono.error(e);
        }

        instance.addMessage(ChatMessage.user(userMessage));
        instance.setStatus(InstanceStatus.PROCESSING);

        ChatCompletionRequest request = ChatCompletionRequest.from(
                instance.getModelName(),
                instance.getMessagesForRequest(),
                instance.getTemperature(),
                instance.getMaxTokens(),
                instance.getTopP()
        );

        return ollamaClient.chatCompletion(request)
                .map(response -> {
                    String assistantContent = response.getAssistantContent();
                    instance.addMessage(ChatMessage.assistant(assistantContent));
                    if (response.usage() != null) {
                        instance.addTokenUsage(response.usage().promptTokens(), response.usage().completionTokens());
                    }
                    instance.setStatus(InstanceStatus.IDLE);
                    return assistantContent;
                })
                .doOnError(e -> {
                    instance.setStatus(InstanceStatus.IDLE);
                    LOG.error("Error sending message to instance '{}': {}", instance.getAlias(), e.getMessage());
                });
    }

    public Mono<String> assignTask(String aliasOrId, String taskDescription) {
        LlmInstance instance;
        try {
            instance = requireInstance(aliasOrId);
        } catch (Exception e) {
            return Mono.error(e);
        }

        instance.setCurrentTask(taskDescription);
        String summary = taskDescription.length() > 200
                ? taskDescription.substring(0, 200) + "..."
                : taskDescription;
        instance.setTaskContextSummary(summary);

        instance.addMessage(ChatMessage.system(
                "You have been assigned a new task. Focus on completing this task. " +
                "Report your progress and findings clearly."
        ));

        return sendMessage(aliasOrId, "TASK ASSIGNMENT: " + taskDescription);
    }

    public void terminateInstance(String aliasOrId) {
        LlmInstance instance = requireInstance(aliasOrId);
        instance.terminate();
        LOG.info("Terminated instance '{}' (id={})", instance.getAlias(), instance.getId());
    }

    public Mono<String> routeMessage(String fromAliasOrId, String toAliasOrId, String message) {
        LlmInstance from, to;
        try {
            from = requireInstance(fromAliasOrId);
            to = requireInstance(toAliasOrId);
        } catch (Exception e) {
            return Mono.error(e);
        }

        String framedMessage = "[Message from instance '" + from.getAlias() + "']: " + message;
        return sendMessage(to.getAlias(), framedMessage);
    }

    public Mono<Map<String, String>> broadcastMessage(List<String> aliases, String message) {
        Collection<LlmInstance> targets;
        try {
            if (aliases == null || aliases.isEmpty()) {
                targets = getAllActiveInstances();
            } else {
                targets = new ArrayList<>();
                for (String alias : aliases) {
                    targets.add(requireInstance(alias));
                }
            }
        } catch (Exception e) {
            return Mono.error(e);
        }

        return Flux.fromIterable(targets)
                .flatMap(instance -> sendMessage(instance.getAlias(), message)
                        .map(resp -> Map.entry(instance.getAlias(), resp))
                        .onErrorReturn(Map.entry(instance.getAlias(), "ERROR")))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /**
     * Autonomous peer-to-peer backchannel loop with individual per-party token accumulation.
     */
    public Mono<String> runAutonomousCollaboration(String instanceA, String instanceB, String taskPrompt, int maxTurns) {
        int limit = (maxTurns <= 0) ? 6 : Math.min(maxTurns, 20);

        LlmInstance objA, objB;
        try {
            objA = requireInstance(instanceA);
            objB = requireInstance(instanceB);
        } catch (Exception e) {
            return Mono.error(e);
        }

        long startTokensA = objA.getTotalTokensUsed();
        long startTokensB = objB.getTotalTokensUsed();

        LOG.info("Starting autonomous backchannel collaboration between '{}' and '{}' for max {} turns", instanceA, instanceB, limit);

        String startMessage = "COLLABORATION ASSIGNMENT with instance '" + instanceB + "': " + taskPrompt +
                "\n\nYou will be speaking directly to '" + instanceB + "'. Provide ONLY your own input as '" + instanceA + "'. Do NOT write responses or dialogue for '" + instanceB + "'. Include '[DONE]' when both of you reach consensus.";

        return sendMessage(instanceA, startMessage)
                .flatMap(responseA -> {
                    StringBuilder transcript = new StringBuilder();
                    transcript.append("═══════════════════════════════════════════════════════════════\n");
                    transcript.append("   AUTONOMOUS BACKCHANNEL COLLABORATION REPORT: ").append(instanceA).append(" & ").append(instanceB).append("\n");
                    transcript.append("═══════════════════════════════════════════════════════════════\n\n");
                    transcript.append("┌─ [Turn 1] ").append(instanceA).append(":\n");
                    transcript.append("│  ").append(responseA.replace("\n", "\n│  ")).append("\n");
                    transcript.append("└─────────────────────────────────────────────\n\n");

                    return runTurnLoop(instanceA, instanceB, responseA, 2, limit, transcript)
                            .map(finalTranscript -> {
                                long endTokensA = objA.getTotalTokensUsed();
                                long endTokensB = objB.getTotalTokensUsed();

                                long consumedA = endTokensA - startTokensA;
                                long consumedB = endTokensB - startTokensB;
                                long totalConsumed = consumedA + consumedB;

                                StringBuilder summary = new StringBuilder(finalTranscript);
                                summary.append("\n─── Session Token Consumption Breakdown ───\n");
                                summary.append(String.format("• %s (%s): %d tokens\n", instanceA, objA.getModelName(), consumedA));
                                summary.append(String.format("• %s (%s): %d tokens\n", instanceB, objB.getModelName(), consumedB));
                                summary.append(String.format("• Total Collaboration Tokens: %d tokens\n", totalConsumed));
                                summary.append("• Antigravity Client Mediation Tokens: 0 tokens (Executed Server-Side)\n");
                                summary.append("═══════════════════════════════════════════════════════════════\n");
                                return summary.toString();
                            });
                });
    }

    private Mono<String> runTurnLoop(String currentSender, String currentReceiver, String lastMessage, int currentTurn, int maxTurns, StringBuilder transcript) {
        if (currentTurn > maxTurns || lastMessage.contains("[DONE]") || lastMessage.contains("[FINISHED]")) {
            transcript.append("✔ Autonomous collaboration completed at Turn ").append(currentTurn - 1).append(".\n");
            return Mono.just(transcript.toString());
        }

        return routeMessage(currentSender, currentReceiver, lastMessage)
                .flatMap(nextMessage -> {
                    transcript.append("┌─ [Turn ").append(currentTurn).append("] ").append(currentReceiver).append(":\n");
                    transcript.append("│  ").append(nextMessage.replace("\n", "\n│  ")).append("\n");
                    transcript.append("└─────────────────────────────────────────────\n\n");

                    return runTurnLoop(currentReceiver, currentSender, nextMessage, currentTurn + 1, maxTurns, transcript);
                });
    }
}
