package com.workflow.core;

public class PipelineRejectedException extends RuntimeException {

    public PipelineRejectedException(String message) {
        super(message);
    }

    public PipelineRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
