package com.workflow.llm.tooluse;

import java.util.List;
import java.util.function.Consumer;

/**
 * Input to {@link com.workflow.llm.LlmClient#completeWithTools(ToolUseRequest,
 * ToolExecutor)}. Immutable builder is provided for readable construction from block
 * code.
 *
 * <p>{@code systemPrompt} is optional; {@code userMessage} is the initial user turn. The
 * loop may append further messages internally (tool_result + follow-up assistant turns)
 * but callers do not control that directly.
 *
 * <p>Cap semantics:
 * <ul>
 *   <li>{@code maxIterations} — hard stop on tool-use iterations (not API calls).
 *   <li>{@code budgetUsdCap} — cumulative cost across all iterations; checked after each.
 *   <li>{@code maxTokens} — per-response token cap sent to the provider.
 * </ul>
 */
public record ToolUseRequest(
    String model,
    String systemPrompt,
    String userMessage,
    List<ToolDefinition> tools,
    int maxTokens,
    double temperature,
    int maxIterations,
    double budgetUsdCap,
    Consumer<String> progressCallback,
    java.nio.file.Path workingDir,
    String completionSignal
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model;
        private String systemPrompt;
        private String userMessage;
        private List<ToolDefinition> tools = List.of();
        private int maxTokens = 4096;
        private double temperature = 1.0;
        private int maxIterations = 40;
        private double budgetUsdCap = 5.0;
        private Consumer<String> progressCallback;
        private java.nio.file.Path workingDir;
        private String completionSignal;

        public Builder model(String v) { this.model = v; return this; }
        public Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Builder userMessage(String v) { this.userMessage = v; return this; }
        public Builder tools(List<ToolDefinition> v) { this.tools = v == null ? List.of() : v; return this; }
        public Builder maxTokens(int v) { this.maxTokens = v; return this; }
        public Builder temperature(double v) { this.temperature = v; return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder budgetUsdCap(double v) { this.budgetUsdCap = v; return this; }
        public Builder progressCallback(Consumer<String> v) { this.progressCallback = v; return this; }
        public Builder workingDir(java.nio.file.Path v) { this.workingDir = v; return this; }
        public Builder completionSignal(String v) { this.completionSignal = v; return this; }

        public ToolUseRequest build() {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
            if (userMessage == null) throw new IllegalArgumentException("userMessage required");
            return new ToolUseRequest(model, systemPrompt, userMessage, tools,
                maxTokens, temperature, maxIterations, budgetUsdCap, progressCallback, workingDir,
                completionSignal);
        }
    }
}
