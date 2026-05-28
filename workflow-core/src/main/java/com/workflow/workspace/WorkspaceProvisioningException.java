package com.workflow.workspace;

/**
 * Thrown when a per-run repo sandbox cannot be provisioned (bad token, unreachable
 * repository, disk failure). {@code RunController} maps this to an HTTP 400 so the
 * customer gets a clear, synchronous error instead of a half-started run.
 */
public class WorkspaceProvisioningException extends RuntimeException {
    public WorkspaceProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
