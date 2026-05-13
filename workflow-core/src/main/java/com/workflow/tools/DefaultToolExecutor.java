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
    private final java.util.Map<String, Tool> extras;
    private final ToolContext context;
    private final ObjectMapper objectMapper;
    private final ToolCallAuditRepository auditRepository;

    /**
     * Per-invocation dedup of read-only tool calls. Small models inside agentic loops
     * sometimes lose track of what they've already searched and re-issue the same
     * Glob/Read/Grep dozens of times — observed 11× repeats of one Glob in FEAT-AP-002
     * impl_server. We return the prior result with a warning header so the agent sees
     * "you've already done this" and (hopefully) breaks out of the loop.
     *
     * <p>Only idempotent read-only tools are deduped. Write/Edit/Bash always run
     * fresh — repeated writes may be intentional (e.g. progress markers).
     */
    private static final java.util.Set<String> READ_ONLY_TOOLS = java.util.Set.of(
        "Read", "Glob", "Grep", "Search");
    private final java.util.Map<String, ToolResult> dedupCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> dedupCount = new java.util.concurrent.ConcurrentHashMap<>();

    public DefaultToolExecutor(ToolRegistry registry, ToolContext context) {
        this(registry, context, null, null, java.util.List.of());
    }

    public DefaultToolExecutor(ToolRegistry registry, ToolContext context,
                               ObjectMapper objectMapper,
                               ToolCallAuditRepository auditRepository) {
        this(registry, context, objectMapper, auditRepository, java.util.List.of());
    }

    /**
     * @param extras per-invocation tools that aren't Spring beans (e.g. {@code McpToolWrapper}
     *               instances built on-demand from the YAML's {@code mcp_servers} list).
     *               These shadow {@link ToolRegistry} entries with the same name.
     */
    public DefaultToolExecutor(ToolRegistry registry, ToolContext context,
                               ObjectMapper objectMapper,
                               ToolCallAuditRepository auditRepository,
                               java.util.List<Tool> extras) {
        if (registry == null) throw new IllegalArgumentException("registry required");
        if (context == null) throw new IllegalArgumentException("context required");
        this.registry = registry;
        this.context = context;
        this.objectMapper = objectMapper;
        this.auditRepository = auditRepository;
        if (extras == null || extras.isEmpty()) {
            this.extras = java.util.Map.of();
        } else {
            java.util.Map<String, Tool> m = new java.util.HashMap<>();
            for (Tool t : extras) if (t != null) m.put(t.name(), t);
            this.extras = java.util.Map.copyOf(m);
        }
    }

    @Override
    public ToolResult execute(ToolCall call) {
        long started = System.currentTimeMillis();
        String toolName = call.toolName();
        ToolResult result;

        Tool tool = extras.get(toolName);
        if (tool == null && registry.has(toolName)) tool = registry.get(toolName);
        if (tool == null) {
            result = ToolResult.error(call.id(),
                "unknown tool: '" + toolName + "' — available: " + availableNames());
            persistAudit(call, result, (int) (System.currentTimeMillis() - started));
            return result;
        }

        // Dedup gate for read-only tools — see field javadoc for the why.
        String dedupKey = null;
        if (READ_ONLY_TOOLS.contains(toolName)) {
            dedupKey = toolName + ":" + (call.input() == null ? "" : call.input().toString());
            ToolResult cached = dedupCache.get(dedupKey);
            if (cached != null) {
                int hits = dedupCount.merge(dedupKey, 1, Integer::sum);
                String warning = String.format(
                    "[DUPLICATE call — same %s args already executed %d×. Use a different query or move on. " +
                    "Cached result below.]\n\n%s", toolName, hits, cached.content());
                result = cached.isError()
                    ? ToolResult.error(call.id(), warning)
                    : ToolResult.ok(call.id(), warning);
                log.info("Tool {} dedup hit (#{} for key)", toolName, hits);
                persistAudit(call, result, (int) (System.currentTimeMillis() - started));
                return result;
            }
        }

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

        if (dedupKey != null) {
            dedupCache.put(dedupKey, result);
            dedupCount.put(dedupKey, 1);
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

    private String availableNames() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (Tool t : registry.all()) names.add(t.name());
        names.addAll(extras.keySet());
        return names.toString();
    }
}
