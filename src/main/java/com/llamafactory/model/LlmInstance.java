package com.llamafactory.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single spawned LLM instance with its own conversational state,
 * generation parameters, and task tracking.
 *
 * <p>Each instance maintains a full conversation history and can be independently
 * assigned tasks, queried, or terminated.</p>
 */
public class LlmInstance {

    private final String id;
    private final String alias;
    private final String modelName;
    private final double temperature;
    private final int maxTokens;
    private final double topP;
    private final List<ChatMessage> conversationHistory;
    private final Instant createdAt;
    private final AtomicLong totalPromptTokens;
    private final AtomicLong totalCompletionTokens;

    private volatile InstanceStatus status;
    private volatile String currentTask;
    private volatile String taskContextSummary;
    private volatile Instant lastActiveAt;
    private volatile long estimatedMemoryMb;

    public LlmInstance(String alias, String modelName, double temperature, int maxTokens, double topP) {
        this.id = UUID.randomUUID().toString();
        this.alias = alias;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.topP = topP;
        this.conversationHistory = Collections.synchronizedList(new ArrayList<>());
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
        this.status = InstanceStatus.IDLE;
        this.totalPromptTokens = new AtomicLong(0);
        this.totalCompletionTokens = new AtomicLong(0);
        this.estimatedMemoryMb = 0;
    }

    // --- Getters ---

    public String getId() {
        return id;
    }

    public String getAlias() {
        return alias;
    }

    public String getModelName() {
        return modelName;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public double getTopP() {
        return topP;
    }

    public List<ChatMessage> getConversationHistory() {
        return Collections.unmodifiableList(new ArrayList<>(conversationHistory));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public InstanceStatus getStatus() {
        return status;
    }

    public String getCurrentTask() {
        return currentTask;
    }

    public String getTaskContextSummary() {
        return taskContextSummary;
    }

    public long getTotalPromptTokens() {
        return totalPromptTokens.get();
    }

    public long getTotalCompletionTokens() {
        return totalCompletionTokens.get();
    }

    public long getTotalTokensUsed() {
        return totalPromptTokens.get() + totalCompletionTokens.get();
    }

    public long getEstimatedMemoryMb() {
        return estimatedMemoryMb;
    }

    // --- Mutators ---

    public void addMessage(ChatMessage message) {
        conversationHistory.add(message);
        this.lastActiveAt = Instant.now();
    }

    public List<ChatMessage> getMessagesForRequest() {
        return new ArrayList<>(conversationHistory);
    }

    public void setStatus(InstanceStatus status) {
        this.status = status;
    }

    public void setCurrentTask(String currentTask) {
        this.currentTask = currentTask;
    }

    public void setTaskContextSummary(String taskContextSummary) {
        this.taskContextSummary = taskContextSummary;
    }

    public void addTokenUsage(long promptTokens, long completionTokens) {
        this.totalPromptTokens.addAndGet(promptTokens);
        this.totalCompletionTokens.addAndGet(completionTokens);
    }

    public void setEstimatedMemoryMb(long estimatedMemoryMb) {
        this.estimatedMemoryMb = estimatedMemoryMb;
    }

    public void terminate() {
        this.status = InstanceStatus.TERMINATED;
        this.currentTask = null;
        this.taskContextSummary = null;
    }

    public int getConversationLength() {
        return conversationHistory.size();
    }
}
