package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ApprovalMode {
    MANUAL("manual"),
    AUTO("auto"),
    AUTO_NOTIFY("auto_notify");

    private final String value;

    ApprovalMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ApprovalMode fromValue(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase();
        for (ApprovalMode mode : values()) {
            if (mode.value.equals(normalized)) return mode;
        }
        throw new IllegalArgumentException("Unknown approval mode: " + value);
    }
}
