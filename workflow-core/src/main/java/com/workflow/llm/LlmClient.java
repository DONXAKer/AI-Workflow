package com.workflow.llm;

import com.workflow.llm.provider.LlmProviderRouter;
import com.workflow.llm.provider.OllamaProviderClient;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Facade over the 5 {@link com.workflow.llm.provider.LlmProviderClient} implementations
 * (OpenRouter, AITunnel, Ollama, vLLM, Claude Code CLI). Blocks inject {@code LlmClient}
 * and call {@code complete}/{@code completeWithMessages}/{@code completeWithTools} —
 * {@link LlmProviderRouter} picks the right provider from the current
 * {@link LlmCallContext}'s preferred provider (pinned per-run by
 * {@code PipelineRunner}) or auto-detection.
 *
 * <p>Historical note: until the May 2026 refactor this class was a 1753-line monolith
 * with all 5 providers' HTTP loops, retry logic, tool-use bodies, and usage recording
 * inline. The behaviour is now distributed across {@code com.workflow.llm.provider.*};
 * this class's public API is preserved 1:1 so the ~14 block-side callers don't change.
 */
@Service
public class LlmClient {

    private final LlmProviderRouter router;
    private final OllamaProviderClient ollama;

    @Autowired
    public LlmClient(LlmProviderRouter router, OllamaProviderClient ollama) {
        this.router = router;
        this.ollama = ollama;
    }

    /** Single-shot completion: system + user message, no tool-use. */
    public String complete(String model, String system, String user, int maxTokens, double temperature) {
        return router.route(model).complete(model, system, user, maxTokens, temperature);
    }

    /** Multi-turn completion accepting a full chat-message history. Used by the
     *  orchestrator continuation-call path. */
    public String completeWithMessages(String model, List<Map<String, String>> messages,
                                       int maxTokens, double temperature) {
        return router.route(model).completeWithMessages(model, messages, maxTokens, temperature);
    }

    /** Agentic tool-use loop. Provider implementations handle retry, two-reminder
     *  injection, history pruning, and per-iteration {@link LlmCall} audit. */
    public ToolUseResponse completeWithTools(ToolUseRequest request, ToolExecutor executor) {
        if (request == null) throw new IllegalArgumentException("request required");
        if (executor == null) throw new IllegalArgumentException("executor required");
        return router.route(request.model()).completeWithTools(request, executor);
    }

    /** Ollama-specific VRAM management. Two blocks ({@code OrchestratorBlock},
     *  {@code AgentWithToolsBlock}) call this before switching Ollama models on
     *  8 GB GPUs to avoid OOM thrashing. Pass {@code null} to unload everything. */
    public void unloadOllamaModelsExcept(String keepLoaded) {
        ollama.unloadModelsExcept(keepLoaded);
    }

    /** Strips {@code <think>...</think>} reasoning blocks from model output.
     *  Reasoning models (qwen3, deepseek-r1) embed thinking in the content field;
     *  callers typically only need the non-reasoning part. */
    public static String stripThinkingBlocks(String text) {
        if (text == null || !text.contains("<think>")) return text;
        return text.replaceAll("(?s)<think>.*?</think>", "").strip();
    }

    /** Removes markdown code fence wrappers ({@code ```lang...```}) from LLM output. */
    public String stripCodeFences(String text) {
        if (text == null) return null;
        String stripped = text.strip();

        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            if (firstNewline > 0) {
                String afterFence = stripped.substring(firstNewline + 1);
                if (afterFence.endsWith("```")) {
                    return afterFence.substring(0, afterFence.length() - 3).strip();
                }
            }
        }
        return stripped;
    }
}
