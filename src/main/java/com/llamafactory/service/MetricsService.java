package com.llamafactory.service;

import com.llamafactory.client.OllamaOpenAiClient;
import com.llamafactory.client.dto.OllamaProcessResponse;
import com.llamafactory.model.LlmInstance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Aggregates metrics asynchronously across active instances and live Ollama processes.
 */
@Singleton
public class MetricsService {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsService.class);
    private static final long BYTES_PER_MB = 1024 * 1024;

    private final InstanceManager instanceManager;
    private final OllamaOpenAiClient ollamaClient;

    @Inject
    public MetricsService(InstanceManager instanceManager, OllamaOpenAiClient ollamaClient) {
        this.instanceManager = instanceManager;
        this.ollamaClient = ollamaClient;
    }

    public Mono<String> generateMetricsReport() {
        var activeInstances = instanceManager.getAllActiveInstances();
        int activeCount = activeInstances.size();

        return ollamaClient.listRunningProcesses()
                .map(processResponse -> {
                    Map<String, OllamaProcessResponse.RunningModel> runningModels = new HashMap<>();
                    if (processResponse.models() != null) {
                        for (var model : processResponse.models()) {
                            runningModels.put(model.name(), model);
                        }
                    }

                    StringBuilder report = new StringBuilder();
                    report.append("═══════════════════════════════════════════\n");
                    report.append("       CALLAMITY METRICS REPORT       \n");
                    report.append("═══════════════════════════════════════════\n\n");
                    report.append("Total Active Instances: ").append(activeCount).append("\n\n");

                    if (activeCount == 0) {
                        report.append("No active instances.\n");
                        appendOllamaServerInfo(report, runningModels);
                        return report.toString();
                    }

                    report.append("─── Per-Instance Breakdown ───\n\n");

                    for (LlmInstance instance : activeInstances) {
                        report.append("┌─ Instance: ").append(instance.getAlias()).append("\n");
                        report.append("│  ID:          ").append(instance.getId()).append("\n");
                        report.append("│  Model:       ").append(instance.getModelName()).append("\n");
                        report.append("│  Status:      ").append(instance.getStatus()).append("\n");

                        String task = instance.getCurrentTask();
                        report.append("│  Current Task:    ").append(task != null ? task : "None").append("\n");
                        report.append("│  Total Tokens:       ").append(instance.getTotalTokensUsed()).append("\n");
                        report.append("│  Conversation Msgs:  ").append(instance.getConversationLength()).append("\n");

                        OllamaProcessResponse.RunningModel runningModel = runningModels.get(instance.getModelName());
                        if (runningModel != null) {
                            long memoryMb = runningModel.size() / BYTES_PER_MB;
                            long vramMb = runningModel.sizeVram() / BYTES_PER_MB;
                            instance.setEstimatedMemoryMb(memoryMb);
                            report.append("│  Total Memory:      ").append(memoryMb).append(" MB\n");
                            report.append("│  VRAM Allocated:    ").append(vramMb).append(" MB\n");
                        } else {
                            report.append("│  Memory:           Not loaded in Ollama\n");
                        }

                        report.append("└────────────────────────────────────\n\n");
                    }

                    appendOllamaServerInfo(report, runningModels);
                    return report.toString();
                });
    }

    private void appendOllamaServerInfo(StringBuilder report, Map<String, OllamaProcessResponse.RunningModel> runningModels) {
        report.append("─── Ollama Server Processes ───\n\n");
        if (runningModels.isEmpty()) {
            report.append("No models currently loaded in Ollama.\n");
        } else {
            for (var entry : runningModels.entrySet()) {
                var model = entry.getValue();
                report.append("  Model: ").append(model.name())
                        .append(" | VRAM: ").append(model.sizeVram() / BYTES_PER_MB).append(" MB\n");
            }
        }
    }
}
