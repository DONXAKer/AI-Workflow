package com.workflow.config;

/**
 * Single error produced by {@link PipelineConfigValidator}.
 *
 * @param code     stable error code (see {@link PipelineConfigValidator} for the catalogue)
 * @param message  human-readable description
 * @param location optional path inside the YAML (e.g. {@code "pipeline[3].depends_on"}); may be {@code null}
 * @param blockId  the block ID this error is associated with, or {@code null} for top-level errors
 */
public record ValidationError(String code, String message, String location, String blockId) {
}
