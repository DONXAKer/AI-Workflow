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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldSchema(
    String name,
    String label,
    String type,
    boolean required,
    Object defaultValue,
    String description,
    Map<String, Object> hints
) {
    public FieldSchema {
        if (hints == null) hints = Map.of();
    }

    public static FieldSchema string(String name, String label, String description) {
        return new FieldSchema(name, label, "string", false, null, description, Map.of());
    }

    public static FieldSchema multilineString(String name, String label, String description) {
        return new FieldSchema(name, label, "string", false, null, description,
            Map.of("multiline", true));
    }

    public static FieldSchema monospaceString(String name, String label, String description) {
        return new FieldSchema(name, label, "string", false, null, description,
            Map.of("multiline", true, "monospace", true));
    }

    public static FieldSchema requiredString(String name, String label, String description) {
        return new FieldSchema(name, label, "string", true, null, description, Map.of());
    }

    public static FieldSchema number(String name, String label, Object defaultValue, String description) {
        return new FieldSchema(name, label, "number", false, defaultValue, description, Map.of());
    }

    public static FieldSchema bool(String name, String label, Object defaultValue, String description) {
        return new FieldSchema(name, label, "boolean", false, defaultValue, description, Map.of());
    }

    public static FieldSchema stringArray(String name, String label, String description) {
        return new FieldSchema(name, label, "string_array", false, null, description, Map.of());
    }

    public static FieldSchema enumField(String name, String label, java.util.List<String> values,
                                        Object defaultValue, String description) {
        return new FieldSchema(name, label, "enum", false, defaultValue, description,
            Map.of("values", values));
    }

    public static FieldSchema blockRef(String name, String label, String description) {
        return new FieldSchema(name, label, "block_ref", false, null, description, Map.of());
    }

    public static FieldSchema toolList(String name, String label, String description) {
        return new FieldSchema(name, label, "tool_list", false, null, description, Map.of());
    }
}
