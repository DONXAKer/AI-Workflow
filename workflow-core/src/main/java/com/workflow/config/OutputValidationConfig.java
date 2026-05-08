package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema-validation rules applied to block output before passing it to the next block.
 *
 * <pre>
 * validate_output:
 *   required: [goal, files_to_touch]
 *   non_empty: [goal]
 *   type_checks:
 *     - { field: files_to_touch, type: list }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutputValidationConfig {

    private List<String> required = new ArrayList<>();

    @JsonProperty("non_empty")
    @JsonAlias({"nonEmpty"})
    private List<String> nonEmpty = new ArrayList<>();

    @JsonProperty("type_checks")
    @JsonAlias({"typeChecks"})
    private List<TypeCheck> typeChecks = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeCheck {
        private String field;
        private String type;

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    public List<String> getRequired() { return required; }
    public void setRequired(List<String> required) { this.required = required != null ? required : new ArrayList<>(); }

    public List<String> getNonEmpty() { return nonEmpty; }
    public void setNonEmpty(List<String> nonEmpty) { this.nonEmpty = nonEmpty != null ? nonEmpty : new ArrayList<>(); }

    public List<TypeCheck> getTypeChecks() { return typeChecks; }
    public void setTypeChecks(List<TypeCheck> typeChecks) { this.typeChecks = typeChecks != null ? typeChecks : new ArrayList<>(); }
}
