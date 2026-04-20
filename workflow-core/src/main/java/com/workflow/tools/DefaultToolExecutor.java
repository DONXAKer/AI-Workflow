package com.workflow.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.llm.LlmCallContext;
import com.workflow.llm.tooluse.ToolCall;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

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
    private final ObjectMapper objectMapper;
    private final ToolCallAuditRepository auditRepository;

    public DefaultToolExecutor(ToolRegistry registry, ToolContext context) {
        this(registry, context, null, null);
    }

    public DefaultToolExecutor(ToolRegistry registry, ToolContext context,
                               ObjectMapper objectMapper,
                               ToolCallAuditRepository auditRepository) {
        if (registry == null) throw new IllegalArgumentException("registry required");
        if (context == null) throw new IllegalArgumentException("context required");
        this.registry = registry;
        this.context = context;
        this.objectMapper = objectMapper;
        this.auditRepository = auditRepository;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        long started = System.currentTimeMillis();
        String toolName = call.toolName();
        ToolResult result;

        if (!registry.has(toolName)) {
            result = ToolResult.error(call.id(),
                "unknown tool: '" + toolName + "' — available: " + registeredNames());
        } else {
            Tool tool = registry.get(toolName);
            try {
                String content = tool.execute(context, call.input());
                result = ToolResult.ok(call.id(), content == null ? "" : content);
            } catch (ToolInvocationException e) {
                log.debug("Tool {} rejected input: {}", toolName, e.getMessage());
                result = ToolResult.error(call.id(), e.getMessage());
            } catch (Exception e) {
                log.warn("Tool {} crashed: {}", toolName, e.getMessage(), e);
                result = ToolResult.error(call.id(),
                    "tool '" + toolName + "' failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        }

        persistAudit(call, result, (int) (System.currentTimeMillis() - started));
        return result;
    }

    private void persistAudit(ToolCall call, ToolResult result, int durationMs) {
        if (auditRepository == null) return;
        try {
            ToolCallAudit row = new ToolCallAudit();
            row.setTimestamp(Instant.now());
            row.setToolName(call.toolName());
            row.setToolUseId(call.id());
            row.setOutputText(truncate(result.content(), 32_000));
            row.setError(result.isError());
            row.setDurationMs(durationMs);
            if (objectMapper != null && call.input() != null) {
                row.setInputJson(truncate(objectMapper.writeValueAsString(call.input()), 32_000));
            }
            LlmCallContext.current().ifPresent(ctx -> {
                row.setRunId(ctx.runId());
                row.setBlockId(ctx.blockId());
            });
            ToolCallIteration.current().ifPresent(row::setIteration);
            row.setProjectSlug(com.workflow.project.ProjectContext.get());
            auditRepository.save(row);
        } catch (Exception e) {
            log.debug("ToolCallAudit persist failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated]";
    }

    private String registeredNames() {
        return registry.all().stream().map(Tool::name).sorted().toList().toString();
    }
}
