package com.workflow.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.llm.tooluse.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared text/JSON utilities used by all {@link LlmProviderClient} implementations.
 *
 * <p>State-less, all methods static. Not a Spring bean — utility class only.
 *
 * <p>Originally lived as private methods on {@code LlmClient}; extracted so all 5
 * providers can share the same context-pruning algorithm, tools-JSON shape, and
 * thinking-block stripping without copy-paste.
 */
final class LlmTextUtils {

    private static final Logger log = LoggerFactory.getLogger(LlmTextUtils.class);

    private LlmTextUtils() {}

    /** Cap on accumulated message-history length before pruning kicks in. */
    static final int MAX_CONTEXT_MSGS = 40;
    /** When pruning, keep this many trailing messages (after the system+initial-user anchor). */
    static final int KEEP_RECENT_MSGS = 28;

    /**
     * Strips {@code <think>...</think>} reasoning blocks from model output. Reasoning
     * models (qwen3, deepseek-r1) embed thinking in the content field; callers
     * typically only need the non-reasoning part.
     */
    static String stripThinkingBlocks(String text) {
        if (text == null || !text.contains("<think>")) return text;
        return text.replaceAll("(?s)<think>.*?</think>", "").strip();
    }

    /** Removes markdown code fence wrappers ({@code ```lang...```}) from LLM output. */
    static String stripCodeFences(String text) {
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

    /**
     * Keeps context size manageable by dropping old assistant+tool pairs when the
     * messages array grows past {@link #MAX_CONTEXT_MSGS}. Always preserves:
     * <ol>
     *   <li>System message ({@code role=system}) if present at index 0
     *   <li>First user message (initial task)
     *   <li>The most recent {@link #KEEP_RECENT_MSGS} messages, walking back to the
     *       nearest assistant turn so tool-result messages don't get orphaned
     * </ol>
     */
    static void pruneContextIfNeeded(ArrayNode messages) {
        if (messages.size() <= MAX_CONTEXT_MSGS) return;

        List<JsonNode> all = new ArrayList<>();
        messages.forEach(all::add);

        int anchor = 0;
        if (!all.isEmpty() && "system".equals(all.get(0).path("role").asText())) anchor++;
        if (anchor < all.size() && "user".equals(all.get(anchor).path("role").asText())) anchor++;

        int tailStart = Math.max(anchor, all.size() - KEEP_RECENT_MSGS);
        while (tailStart > anchor && !"assistant".equals(all.get(tailStart).path("role").asText())) {
            tailStart--;
        }

        int dropped = tailStart - anchor;
        if (dropped <= 0) return;

        log.info("Context pruning: dropping {} old messages (total was {})", dropped, all.size());
        messages.removeAll();
        for (int i = 0; i < anchor; i++) messages.add(all.get(i));
        for (int i = tailStart; i < all.size(); i++) messages.add(all.get(i));
    }

    /**
     * Builds the OpenAI-compat {@code tools} array from the platform's
     * {@link ToolDefinition} list. Shape is identical for OpenRouter / AITunnel /
     * Ollama / vLLM — each item is {@code {type:"function", function:{name, description, parameters}}}.
     *
     * <p>Tools without an explicit {@code inputSchema} get an empty
     * {@code {"type":"object","properties":{}}} schema so the API doesn't reject
     * the call.
     */
    static ArrayNode buildToolsJson(List<ToolDefinition> tools, ObjectMapper objectMapper) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (tools == null) return arr;
        for (ToolDefinition t : tools) {
            ObjectNode wrapper = arr.addObject();
            wrapper.put("type", "function");
            ObjectNode fn = wrapper.putObject("function");
            fn.put("name", t.name());
            if (t.description() != null) {
                fn.put("description", t.description());
            }
            if (t.inputSchema() != null) {
                fn.set("parameters", t.inputSchema());
            } else {
                ObjectNode params = fn.putObject("parameters");
                params.put("type", "object");
                params.putObject("properties");
            }
        }
        return arr;
    }
}
