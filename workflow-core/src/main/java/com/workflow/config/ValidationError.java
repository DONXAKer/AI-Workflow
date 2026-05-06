package com.workflow.config;

/**
 * Single error produced by {@link PipelineConfigValidator}.
 *
 * @param code     stable error code (see {@link PipelineConfigValidator} for the catalogue)
 * @param message  human-readable description
 * @param location optional path inside the YAML (e.g. {@code "pipeline[3].depends_on"}); may be {@code null}
 * @param blockId  the block ID this error is associated with, or {@code null} for top-level errors
 * @param severity {@link Severity#ERROR} (blocks save/run), {@link Severity#WARN} or {@link Severity#INFO} (advisory only)
 */
public record ValidationError(String code, String message, String location, String blockId, Severity severity) {
    public ValidationError {
        if (severity == null) severity = Severity.ERROR;
    }

    /** Backward-compat constructor — defaults severity to ERROR. */
    public ValidationError(String code, String message, String location, String blockId) {
        this(code, message, location, blockId, Severity.ERROR);
    }

    public static ValidationError error(String code, String message, String location, String blockId) {
        return new ValidationError(code, message, location, blockId, Severity.ERROR);
    }

    public static ValidationError warn(String code, String message, String location, String blockId) {
        return new ValidationError(code, message, location, blockId, Severity.WARN);
    }

    public static ValidationError info(String code, String message, String location, String blockId) {
        return new ValidationError(code, message, location, blockId, Severity.INFO);
    }
}
