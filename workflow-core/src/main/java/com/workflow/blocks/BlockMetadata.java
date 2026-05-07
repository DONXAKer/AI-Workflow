package com.workflow.blocks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * UI-editor metadata for a block type. Returned from {@link Block#getMetadata()} and
 * exposed to the frontend via {@code GET /api/blocks/registry} so the Pipeline Editor
 * can render appropriate forms for each block.
 *
 * @param label           human-readable display name (e.g. "Shell exec")
 * @param category        one of: input | agent | verify | ci | infra | output | general
 * @param phase           pipeline phase this block belongs to. {@link Phase#ANY} marks
 *                        polymorphic blocks that operators must pin per-instance.
 * @param configFields    list of {@link FieldSchema} entries describing block.config keys.
 *                        Empty list = UI shows {@code RawJsonFallback} unless
 *                        {@code hasCustomForm=true}.
 * @param hasCustomForm   if true, UI uses a dedicated React component rather than the
 *                        generic field renderer (e.g. {@code agent_with_tools},
 *                        {@code verify})
 * @param uiHints         block-level rendering hints (free-form map for future use)
 * @param outputs         list of {@link FieldSchema} entries describing fields the
 *                        block produces in its run-output map. Drives the
 *                        {@code OutputsRefPicker} autocomplete for {@code ${block.field}}
 *                        references and the WARN-level {@code REF_UNKNOWN_FIELD}
 *                        validator check. Empty list = block hasn't declared its
 *                        output schema yet (warn-only behaviour).
 * @param recommendedRank integer hint for creation-wizard preset selection (PR-3).
 *                        Higher = more typically chosen for the block's phase.
 *                        {@code 0} = no preference declared.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlockMetadata(
    String label,
    String category,
    Phase phase,
    List<FieldSchema> configFields,
    boolean hasCustomForm,
    Map<String, Object> uiHints,
    List<FieldSchema> outputs,
    int recommendedRank
) {
    public BlockMetadata {
        if (configFields == null) configFields = List.of();
        if (uiHints == null) uiHints = Map.of();
        if (phase == null) phase = Phase.ANY;
        if (outputs == null) outputs = List.of();
    }

    /**
     * Backward-compat 5-arg constructor for blocks declared before phases existed.
     * Defaults phase to {@link Phase#ANY} — these blocks will emit a WARN at
     * validation until their {@code getMetadata()} is updated to pass an
     * explicit phase. Outputs default to empty list, recommendedRank to 0.
     * Callers should migrate to the canonical 8-arg form.
     */
    public BlockMetadata(String label, String category, List<FieldSchema> configFields,
                         boolean hasCustomForm, Map<String, Object> uiHints) {
        this(label, category, Phase.ANY, configFields, hasCustomForm, uiHints, List.of(), 0);
    }

    /**
     * Backward-compat 6-arg constructor — phase-aware but pre-PR-1 (no outputs/rank).
     * Outputs default to empty list, recommendedRank to 0. Most existing block
     * implementations use this form; callers wanting to declare outputs / preset rank
     * should migrate to the canonical 8-arg form.
     */
    public BlockMetadata(String label, String category, Phase phase, List<FieldSchema> configFields,
                         boolean hasCustomForm, Map<String, Object> uiHints) {
        this(label, category, phase, configFields, hasCustomForm, uiHints, List.of(), 0);
    }

    /**
     * Default metadata for blocks that don't override {@link Block#getMetadata()}.
     * Phase is resolved from the canonical {@link Phase#forBlockType(String)}
     * map — blocks listed there get a meaningful phase even without a custom
     * {@code getMetadata()}, unmapped blocks fall back to {@link Phase#ANY}.
     * Outputs default to empty list, recommendedRank to 0.
     */
    public static BlockMetadata defaultFor(String name) {
        return new BlockMetadata(name, "general", Phase.forBlockType(name), List.of(),
            false, Map.of(), List.of(), 0);
    }
}
