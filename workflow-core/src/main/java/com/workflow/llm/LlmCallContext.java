package com.workflow.llm;

import java.util.Optional;
import java.util.UUID;

/**
 * Thread-local context tagged onto every {@link LlmClient#complete} call so the cost
 * tracker can associate the call with a specific pipeline run and block.
 *
 * <p>{@link PipelineRunner} sets the context around each block execution in a try-finally.
 * Calls outside a pipeline (e.g. {@code RunReturnService} comment structuring) leave the
 * context empty and the {@code runId}/{@code blockId} fields on {@link LlmCall} stay null.
 */
public final class LlmCallContext {

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    private LlmCallContext() {}

    public static void set(UUID runId, String blockId) {
        CTX.set(new Context(runId, blockId));
    }

    public static void clear() {
        CTX.remove();
    }

    public static Optional<Context> current() {
        return Optional.ofNullable(CTX.get());
    }

    public record Context(UUID runId, String blockId) {}
}
