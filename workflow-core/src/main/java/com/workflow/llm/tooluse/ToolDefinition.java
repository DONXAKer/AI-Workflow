package com.workflow.llm.tooluse;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Descriptor for a tool that the LLM can invoke during a tool-use session.
 *
 * <p>The {@code inputSchema} is a JSON Schema (type: "object") passed verbatim to the
 * provider API. For Anthropic format this lands in the {@code input_schema} field of each
 * tool definition.
 *
 * <p>Name must be unique within a single {@link ToolUseRequest}.
 */
public record ToolDefinition(String name, String description, ObjectNode inputSchema) {
}
