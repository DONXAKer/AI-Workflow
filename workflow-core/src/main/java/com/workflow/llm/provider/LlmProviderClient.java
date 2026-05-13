package com.workflow.llm.provider;

import com.workflow.llm.LlmProvider;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for a single LLM transport (OpenRouter, AITunnel, Ollama, vLLM,
 * Claude Code CLI). Each implementation owns the full request lifecycle for its
 * provider: HTTP client construction, message shape, retry/error handling, tool-use
 * loop (if any), and usage recording.
 *
 * <p>The {@link com.workflow.llm.LlmClient} Facade no longer holds any provider
 * logic — it routes through {@code LlmProviderRouter} which picks the right
 * implementation based on the current {@link com.workflow.llm.LlmCallContext}
 * preferred provider (and, only for CLI, a model-name heuristic via
 * {@link #canHandle(String)}).
 */
public interface LlmProviderClient {

    /** Provider enum value this client implements. Used by the router for lookup
     *  and by usage recording so {@link com.workflow.llm.LlmCall#provider} is set
     *  consistently across all 5 transports. */
    LlmProvider providerType();

    /** Single-shot completion: system + user message, no tool-use. */
    String complete(String model, String system, String user, int maxTokens, double temperature);

    /** Multi-turn completion accepting a full chat-message history. Used by the
     *  orchestrator continuation-call path which feeds back a previous assistant
     *  turn and asks the model to continue from where it stopped. Each map must
     *  have keys {@code role} (system|user|assistant) and {@code content}. */
    String completeWithMessages(String model, List<Map<String, String>> messages,
                                int maxTokens, double temperature);

    /** Agentic tool-use loop. Each iteration posts messages+tools to the provider,
     *  dispatches tool_calls to the executor, appends results to message history,
     *  and loops until end_turn / max_iterations / budget / etc.
     *
     *  <p>Default implementation throws {@link UnsupportedOperationException} —
     *  providers that support tool-use must override. (All 5 current providers
     *  override, but the default keeps the door open for future read-only mocks.) */
    default ToolUseResponse completeWithTools(ToolUseRequest request, ToolExecutor executor) {
        throw new UnsupportedOperationException(
            providerType() + " does not implement completeWithTools");
    }

    /** Router hint: can this client handle the given model identifier purely from
     *  name shape, without an explicit {@link com.workflow.llm.LlmCallContext}
     *  preferred-provider pin? Only used by {@code ClaudeCliProviderClient} (which
     *  routes anthropic/ + bare preset names through the CLI when integration is
     *  available). ThreadLocal preference always wins over this. */
    default boolean canHandle(String model) {
        return false;
    }
}
