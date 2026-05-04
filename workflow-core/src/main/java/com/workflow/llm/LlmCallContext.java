package com.workflow.llm;

import java.util.Optional;
import java.util.UUID;

/**
 * Thread-local context tagged onto every {@link LlmClient#complete} call so the cost
 * tracker can associate the call with a specific pipeline run and block.
 *
 * <p>{@link com.workflow.core.PipelineRunner} sets the context around each block
 * execution in a try-finally. Calls outside a pipeline (e.g. {@code RunReturnService}
 * comment structuring) leave the context empty and the {@code runId}/{@code blockId}
 * fields on {@link LlmCall} stay null.
 *
 * <p>The {@code preferredProvider} carries the run-level provider preference (typically
 * resolved from {@code Project.defaultProvider} at run start). When non-null, it
 * overrides the auto-detection in {@link LlmClient}: every LLM call inside the run
 * routes through the chosen provider, regardless of model name. This is the mechanism
 * that turns the "default LLM provider" project setting into actual behavior across
 * every agentic block.
 */
public final class LlmCallContext {

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    private LlmCallContext() {}

    /** Legacy two-arg setter — preferredProvider stays null (no override). */
    public static void set(UUID runId, String blockId) {
        CTX.set(new Context(runId, blockId, null));
    }

    /** Sets full context including the run-level provider override. */
    public static void set(UUID runId, String blockId, LlmProvider preferredProvider) {
        CTX.set(new Context(runId, blockId, preferredProvider));
    }

    public static void clear() {
        CTX.remove();
    }

    public static Optional<Context> current() {
        return Optional.ofNullable(CTX.get());
    }

    public record Context(UUID runId, String blockId, LlmProvider preferredProvider) {}
}
