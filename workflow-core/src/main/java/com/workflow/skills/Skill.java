package com.workflow.skills;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * A skill is a named capability exposed to LLM agents as a callable tool.
 * Implementations are registered as Spring beans and discovered automatically.
 */
public interface Skill {

    /** Unique identifier used to reference the skill in pipeline YAML (e.g. "read_file"). */
    String getName();

    /** Human-readable description passed to the LLM in the tool definition. */
    String getDescription();

    /**
     * JSON Schema (type: "object") describing the tool's input parameters.
     * Used to build the tool definition sent to the LLM API.
     */
    ObjectNode getInputSchema();

    /**
     * Execute the skill with the given parameters parsed from the LLM's tool call.
     *
     * @param params Map of parameter name → value (matching the input schema)
     * @return Result object — will be serialised to JSON and returned as the tool result
     * @throws Exception if execution fails
     */
    Object execute(Map<String, Object> params) throws Exception;
}
