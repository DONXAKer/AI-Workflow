package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single structural check rule for the verify block.
 * Rules: min_length, max_length, min_items, max_items, one_of, not_empty, regex, gt, lt
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldCheckConfig {

    private String field;
    private String rule;
    private Object value;
    private String message = "";

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message != null ? message : ""; }
}
