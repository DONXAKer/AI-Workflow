package com.workflow.tools;

import java.util.Optional;

/**
 * Thread-local carrying the current tool-use loop iteration index, set by
 * {@link com.workflow.llm.LlmClient#completeWithTools} around each
 * {@code executor.execute(call)} invocation.
 *
 * <p>Read by {@link DefaultToolExecutor} when writing a {@code ToolCallAudit} row, so
 * each audit entry can be correlated with the {@code LlmCall} row of the same iteration
 * (both share {@code iteration}, {@code runId}, and {@code blockId}).
 *
 * <p>Outside a tool-use loop the context is empty and audit rows record {@code null}.
 */
public final class ToolCallIteration {

    private static final ThreadLocal<Integer> CTX = new ThreadLocal<>();

    private ToolCallIteration() {}

    public static void set(int iteration) { CTX.set(iteration); }

    public static void clear() { CTX.remove(); }

    public static Optional<Integer> current() { return Optional.ofNullable(CTX.get()); }
}
