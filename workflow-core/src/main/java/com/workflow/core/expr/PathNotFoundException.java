package com.workflow.core.expr;

/**
 * Thrown when a {@code $.path} or {@code ${...}} reference cannot be resolved against
 * the current run's outputs. The plan calls this out explicitly: missing references
 * must fail loudly, never evaluate to silent empty, so a typo in YAML surfaces during
 * pipeline execution rather than being smuggled downstream as an empty string.
 */
public class PathNotFoundException extends RuntimeException {

    public PathNotFoundException(String message) {
        super(message);
    }
}
