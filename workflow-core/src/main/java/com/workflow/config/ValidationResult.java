package com.workflow.config;

import java.util.List;

/**
 * Outcome of {@link PipelineConfigValidator#validate(PipelineConfig)}.
 *
 * <p>{@code valid} is {@code true} iff {@code errors} is empty. Errors are collected in
 * stable order: top-level checks first, then per-block checks in pipeline declaration order.
 */
public record ValidationResult(boolean valid, List<ValidationError> errors) {

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult of(List<ValidationError> errors) {
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }
}
