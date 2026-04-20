package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One capability exposed to an LLM agent through the tool-use loop. Implementations are
 * Spring beans discovered by {@link ToolRegistry}.
 *
 * <p>A {@code Tool} is provider-agnostic — it describes itself via {@link
 * #inputSchema(ObjectMapper)} and executes against a {@link ToolContext}. The LLM-facing
 * envelope ({@code id}, {@code is_error} flag, iteration loop) is added by
 * {@link DefaultToolExecutor}, so tools do not know anything about Anthropic/OpenAI
 * message formats.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #execute} returns a string that becomes the {@code tool_result} content
 *       shown to the LLM.</li>
 *   <li>For user-visible errors (bad input, path out of scope, file not found), throw —
 *       the executor converts the exception to {@code is_error:true}, the LLM decides
 *       how to react.</li>
 *   <li>Side effects must be idempotent where possible: a retry after a partial failure
 *       should not produce divergent state.</li>
 * </ul>
 */
public interface Tool {

    String name();

    String description();

    ObjectNode inputSchema(ObjectMapper objectMapper);

    String execute(ToolContext context, JsonNode input) throws Exception;
}
