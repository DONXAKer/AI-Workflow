package com.workflow.core;

public class OutputValidationException extends RuntimeException {

    public OutputValidationException(String blockId, String field, String reason) {
        super("Output validation failed for block '" + blockId + "', field '" + field + "': " + reason);
    }
}
