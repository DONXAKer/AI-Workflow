package com.workflow.config;

import java.util.List;

/**
 * Outcome of {@link PipelineConfigValidator#validate(PipelineConfig)}.
 *
 * <p>{@code valid} is {@code true} iff there are no {@link Severity#ERROR} entries.
 * {@link Severity#WARN} and {@link Severity#INFO} entries surface in {@code errors}
 * for the editor to display but never abort save / run / explicit-validate.
 *
 * <p>Errors are collected in stable order: top-level checks first, then per-block
 * checks in pipeline declaration order.
 */
public record ValidationResult(boolean valid, List<ValidationError> errors) {

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult of(List<ValidationError> errors) {
        boolean hasError = errors.stream().anyMatch(e -> e.severity() == Severity.ERROR);
        return new ValidationResult(!hasError, List.copyOf(errors));
    }
}
