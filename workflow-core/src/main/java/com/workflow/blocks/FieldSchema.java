package com.workflow.blocks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Schema for a single configurable field of a block (UI editor descriptor).
 *
 * <p>Drives the generic block-form renderer in the Pipeline Editor: the UI looks at
 * {@code type} and {@code hints} to pick a control (text input, textarea, chip-list,
 * select, etc).
 *
 * <p>{@code type} is one of:
 * <ul>
 *   <li>{@code string} — single-line input; multi-line if {@code hints.multiline=true};
 *       monospace if {@code hints.monospace=true}.</li>
 *   <li>{@code number} — numeric input (integer or decimal — UI infers from defaultValue).</li>
 *   <li>{@code boolean} — checkbox/toggle.</li>
 *   <li>{@code string_array} — chip-style input (comma-separated entry).</li>
 *   <li>{@code enum} — select; values come from {@code hints.values} (List of strings).</li>
 *   <li>{@code block_ref} — select populated from current pipeline block IDs.</li>
 *   <li>{@code tool_list} — special widget for {@code allowed_tools} (Read, Write, Edit,
 *       Glob, Grep, Bash).</li>
 * </ul>
 *
 * <p>{@code level} controls visibility tier in the side-panel sectioned UI:
 * {@code "essential"} fields render in the always-open Essentials section; {@code "advanced"}
 * fields render in the collapsed Advanced section. When {@code null} (legacy callers /
 * static factories), the level defaults to {@code "essential"} for {@code required} fields
 * and {@code "advanced"} otherwise — this keeps required fields visible by default without
 * forcing every existing block to migrate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldSchema(
    String name,
    String label,
    String type,
    boolean required,
    Object defaultValue,
    String description,
    Map<String, Object> hints,
    String level
) {
    public FieldSchema {
        if (hints == null) hints = Map.of();
        if (level == null) level = required ? "essential" : "advanced";
    }

    /**
     * Backward-compat 7-arg constructor — defaults {@code level} from {@code required}
     * (required → essential, optional → advanced). Existing callers that don't care
     * about the new field tier keep working unchanged.
     */
    public FieldSchema(String name, String label, String type, boolean required,
                       Object defaultValue, String description, Map<String, Object> hints) {
        this(name, label, type, required, defaultValue, description, hints, null);
    }

    public static FieldSchema string(String name, String label, String description) {
        return new FieldSchema(name, label, "string", false, null, description, Map.of(), null);
    }

    public static FieldSchema multilineString(String name, String label, String description) {
        return new FieldSchema(name, label, "string", false, null, description,
            Map.of("multiline", true), null);
    }

    public static FieldSchema monospaceString(String name, String label, String description) {
        return new FieldSchema(name, label, "string", false, null, description,
            Map.of("multiline", true, "monospace", true), null);
    }

    public static FieldSchema requiredString(String name, String label, String description) {
        return new FieldSchema(name, label, "string", true, null, description, Map.of(), null);
    }

    public static FieldSchema number(String name, String label, Object defaultValue, String description) {
        return new FieldSchema(name, label, "number", false, defaultValue, description, Map.of(), null);
    }

    public static FieldSchema bool(String name, String label, Object defaultValue, String description) {
        return new FieldSchema(name, label, "boolean", false, defaultValue, description, Map.of(), null);
    }

    public static FieldSchema stringArray(String name, String label, String description) {
        return new FieldSchema(name, label, "string_array", false, null, description, Map.of(), null);
    }

    public static FieldSchema enumField(String name, String label, java.util.List<String> values,
                                        Object defaultValue, String description) {
        return new FieldSchema(name, label, "enum", false, defaultValue, description,
            Map.of("values", values), null);
    }

    public static FieldSchema blockRef(String name, String label, String description) {
        return new FieldSchema(name, label, "block_ref", false, null, description, Map.of(), null);
    }

    public static FieldSchema toolList(String name, String label, String description) {
        return new FieldSchema(name, label, "tool_list", false, null, description, Map.of(), null);
    }

    /**
     * Returns a copy of this {@link FieldSchema} with the level set explicitly. Useful
     * for the (rare) cases where the default-from-required heuristic is wrong — e.g.
     * an optional field that should render in Essentials, or a required field intentionally
     * hidden in Advanced.
     */
    public FieldSchema withLevel(String newLevel) {
        return new FieldSchema(name, label, type, required, defaultValue, description, hints, newLevel);
    }

    /**
     * Convenience: build an output-only field descriptor (used in
     * {@link BlockMetadata#outputs()}). Outputs are produced by the block at runtime,
     * so {@code required} is meaningless — fixed at {@code false}; level defaults to
     * {@code "essential"} since outputs are by nature things the operator may want to
     * reference downstream.
     */
    public static FieldSchema output(String name, String label, String type, String description) {
        return new FieldSchema(name, label, type, false, null, description, Map.of(), "essential");
    }
}
