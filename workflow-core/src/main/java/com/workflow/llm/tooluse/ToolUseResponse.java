package com.workflow.llm.tooluse;

import java.util.List;

/**
 * Result of a {@link com.workflow.llm.LlmClient#completeWithTools(ToolUseRequest,
 * ToolExecutor)} invocation.
 *
 * <p>{@code finalText} is the assistant's last text content (may be empty if the loop
 * stopped before the model produced a concluding turn — check {@code stopReason}).
 *
 * <p>{@code toolCallHistory} lists every tool call made, in order, with the corresponding
 * result content. The block layer uses this for audit and approval UI.
 */
public record ToolUseResponse(
    String finalText,
    StopReason stopReason,
    List<ToolCallTrace> toolCallHistory,
    int iterationsUsed,
    int totalInputTokens,
    int totalOutputTokens,
    double totalCostUsd
) {

    /** One row of {@link #toolCallHistory} — a tool call paired with its executed result. */
    public record ToolCallTrace(int iteration, ToolCall call, ToolResult result) {}
}
