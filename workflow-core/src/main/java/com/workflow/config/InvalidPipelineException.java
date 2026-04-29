package com.workflow.config;

import java.util.stream.Collectors;

/**
 * Thrown when a {@link PipelineConfig} fails validation by
 * {@link PipelineConfigValidator}. The full {@link ValidationResult} is preserved so
 * callers (REST controllers, CLI, writer) can surface the structured error list to the
 * operator.
 */
public class InvalidPipelineException extends RuntimeException {

    private final ValidationResult result;

    public InvalidPipelineException(ValidationResult result) {
        super(buildMessage(result));
        this.result = result;
    }

    public ValidationResult getResult() {
        return result;
    }

    private static String buildMessage(ValidationResult result) {
        if (result == null || result.errors() == null || result.errors().isEmpty()) {
            return "Invalid pipeline config";
        }
        String details = result.errors().stream()
            .map(e -> "[" + e.code() + "] "
                + (e.blockId() != null ? "block '" + e.blockId() + "': " : "")
                + e.message())
            .collect(Collectors.joining("; "));
        return "Invalid pipeline config: " + details;
    }
}
