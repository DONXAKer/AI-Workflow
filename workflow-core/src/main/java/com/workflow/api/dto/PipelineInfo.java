package com.workflow.api.dto;

import java.util.Objects;

/**
 * DTO для информации о pipeline конфигурации.
 * Содержит метаданные о конфигурации и источник (platform/project).
 */
public record PipelineInfo(
    String name,
    String path,
    String source,
    String description,
    String pipelineName
) {
    public PipelineInfo {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(source, "source cannot be null");
    }
}