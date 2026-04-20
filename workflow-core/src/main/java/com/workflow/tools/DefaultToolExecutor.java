package com.workflow.tools;

import com.workflow.llm.tooluse.ToolCall;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link ToolExecutor} that dispatches each {@link ToolCall} to the matching
 * {@link Tool} in {@link ToolRegistry}, carrying a block-scoped {@link ToolContext}
 * (working dir + bash allowlist).
 *
 * <p>Not a Spring bean — one instance per block invocation, constructed by
 * {@code agent_with_tools} with the block's config. The same {@link ToolRegistry}
 * singleton is reused across invocations.
 *
 * <p>All expected failures ({@link ToolInvocationException}, unknown tool) are converted
 * to {@code is_error:true} results so the LLM can see what went wrong and adjust.
 * Unchecked exceptions from tools (NPE, I/O crash) also become error results, but with
 * a shorter message — the exception class name only — to avoid leaking stack traces into
 * the model's context.
 */
public class DefaultToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

    private final ToolRegistry registry;
    private final ToolContext context;

    public DefaultToolExecutor(ToolRegistry registry, ToolContext context) {
        if (registry == null) throw new IllegalArgumentException("registry required");
        if (context == null) throw new IllegalArgumentException("context required");
        this.registry = registry;
        this.context = context;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String toolName = call.toolName();
        if (!registry.has(toolName)) {
            return ToolResult.error(call.id(),
                "unknown tool: '" + toolName + "' — available: " + registeredNames());
        }
        Tool tool = registry.get(toolName);
        try {
            String content = tool.execute(context, call.input());
            return ToolResult.ok(call.id(), content == null ? "" : content);
        } catch (ToolInvocationException e) {
            log.debug("Tool {} rejected input: {}", toolName, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        } catch (Exception e) {
            log.warn("Tool {} crashed: {}", toolName, e.getMessage(), e);
            return ToolResult.error(call.id(),
                "tool '" + toolName + "' failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private String registeredNames() {
        return registry.all().stream().map(Tool::name).sorted().toList().toString();
    }
}
