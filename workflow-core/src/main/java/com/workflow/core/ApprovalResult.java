package com.workflow.core;

import java.util.Map;

public class ApprovalResult {

    private final String status;
    private final Map<String, Object> output;
    private final boolean skipFuture;
    private final String jumpTarget;

    private ApprovalResult(Builder builder) {
        this.status = builder.status;
        this.output = builder.output;
        this.skipFuture = builder.skipFuture;
        this.jumpTarget = builder.jumpTarget;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public boolean isSkipFuture() {
        return skipFuture;
    }

    public String getJumpTarget() {
        return jumpTarget;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String status;
        private Map<String, Object> output;
        private boolean skipFuture = false;
        private String jumpTarget = null;

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder output(Map<String, Object> output) {
            this.output = output;
            return this;
        }

        public Builder skipFuture(boolean skipFuture) {
            this.skipFuture = skipFuture;
            return this;
        }

        public Builder jumpTarget(String jumpTarget) {
            this.jumpTarget = jumpTarget;
            return this;
        }

        public ApprovalResult build() {
            return new ApprovalResult(this);
        }
    }
}
