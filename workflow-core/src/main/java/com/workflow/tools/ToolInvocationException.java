package com.workflow.tools;

/**
 * Thrown by a {@link Tool} or by validation helpers ({@link PathScope}, deny-list checks,
 * bash allowlist) when an invocation is rejected for a reason the LLM should see.
 *
 * <p>{@link DefaultToolExecutor} catches this and converts it to an {@code is_error:true}
 * tool_result so the model can adjust and retry. Unlike generic {@code RuntimeException},
 * this is reserved for expected, user-facing failures — infrastructure bugs (NPE, I/O
 * crash) still bubble up and become generic error results.
 */
public class ToolInvocationException extends RuntimeException {

    public ToolInvocationException(String message) {
        super(message);
    }

    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
