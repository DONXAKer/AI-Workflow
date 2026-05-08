package com.workflow.core;

import com.workflow.config.OutputValidationConfig;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class OutputValidator {

    private OutputValidator() {}

    /**
     * Validates block output against the declared schema. Throws {@link OutputValidationException}
     * on the first violation so the block is marked failed and the run stops.
     */
    public static void validate(Map<String, Object> output, OutputValidationConfig config, String blockId) {
        if (config == null) return;

        for (String field : config.getRequired()) {
            if (!output.containsKey(field) || output.get(field) == null) {
                throw new OutputValidationException(blockId, field, "required field is missing or null");
            }
        }

        for (String field : config.getNonEmpty()) {
            Object value = output.get(field);
            if (value == null) {
                throw new OutputValidationException(blockId, field, "non_empty field is null");
            }
            if (value instanceof String s && s.isBlank()) {
                throw new OutputValidationException(blockId, field, "non_empty field is blank");
            }
            if (value instanceof Collection<?> c && c.isEmpty()) {
                throw new OutputValidationException(blockId, field, "non_empty field is an empty list");
            }
            if (value instanceof Map<?, ?> m && m.isEmpty()) {
                throw new OutputValidationException(blockId, field, "non_empty field is an empty object");
            }
        }

        for (OutputValidationConfig.TypeCheck tc : config.getTypeChecks()) {
            String field = tc.getField();
            String expectedType = tc.getType();
            if (field == null || expectedType == null) continue;
            if (!output.containsKey(field)) continue; // required check handles missing

            Object value = output.get(field);
            boolean ok = switch (expectedType.toLowerCase()) {
                case "string" -> value instanceof String;
                case "number" -> value instanceof Number;
                case "boolean" -> value instanceof Boolean;
                case "list", "array" -> value instanceof List || value instanceof Collection;
                case "object", "map" -> value instanceof Map;
                default -> true; // unknown type — skip
            };
            if (!ok) {
                throw new OutputValidationException(blockId, field,
                    "expected type " + expectedType + " but got " + (value == null ? "null" : value.getClass().getSimpleName()));
            }
        }
    }
}
