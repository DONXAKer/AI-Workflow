package com.workflow.llm.tooluse;

/**
 * Result of executing a {@link ToolCall}. The {@code content} is passed back to the LLM
 * as the tool_result content. When {@code isError} is true, the LLM sees an error marker
 * and can decide how to respond (retry with different params, abandon, ask user, etc).
 */
public record ToolResult(String toolUseId, String content, boolean isError) {

    public static ToolResult ok(String toolUseId, String content) {
        return new ToolResult(toolUseId, content, false);
    }

    public static ToolResult error(String toolUseId, String message) {
        return new ToolResult(toolUseId, message, true);
    }
}
