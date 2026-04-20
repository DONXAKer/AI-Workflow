package com.workflow.llm.tooluse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One tool invocation requested by the LLM during a tool-use iteration.
 *
 * <p>{@code id} is the provider-issued identifier used to correlate with the
 * corresponding {@link ToolResult} in the next turn.
 */
public record ToolCall(String id, String toolName, JsonNode input) {
}
