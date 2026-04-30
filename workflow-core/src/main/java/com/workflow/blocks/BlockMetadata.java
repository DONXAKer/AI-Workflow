package com.workflow.blocks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * UI-editor metadata for a block type. Returned from {@link Block#getMetadata()} and
 * exposed to the frontend via {@code GET /api/blocks/registry} so the Pipeline Editor
 * can render appropriate forms for each block.
 *
 * @param label         human-readable display name (e.g. "Shell exec")
 * @param category      one of: input | agent | verify | ci | infra | output | general
 * @param configFields  list of {@link FieldSchema} entries describing block.config keys.
 *                      Empty list = UI shows {@code RawJsonFallback} unless
 *                      {@code hasCustomForm=true}.
 * @param hasCustomForm if true, UI uses a dedicated React component rather than the
 *                      generic field renderer (e.g. {@code agent_with_tools},
 *                      {@code verify})
 * @param uiHints       block-level rendering hints (free-form map for future use)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlockMetadata(
    String label,
    String category,
    List<FieldSchema> configFields,
    boolean hasCustomForm,
    Map<String, Object> uiHints
) {
    public BlockMetadata {
        if (configFields == null) configFields = List.of();
        if (uiHints == null) uiHints = Map.of();
    }

    /** Default metadata for blocks that don't override {@link Block#getMetadata()}. */
    public static BlockMetadata defaultFor(String name) {
        return new BlockMetadata(name, "general", List.of(), false, Map.of());
    }
}
