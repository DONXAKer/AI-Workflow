package com.workflow.core;

import java.util.Map;

public class JumpToBlockException extends RuntimeException {

    private final String targetBlockId;
    private final Map<String, Map<String, Object>> injectedOutputs;

    public JumpToBlockException(String targetBlockId, Map<String, Map<String, Object>> injectedOutputs) {
        super("Jump to block: " + targetBlockId);
        this.targetBlockId = targetBlockId;
        this.injectedOutputs = injectedOutputs;
    }

    public String getTargetBlockId() {
        return targetBlockId;
    }

    public Map<String, Map<String, Object>> getInjectedOutputs() {
        return injectedOutputs;
    }
}
