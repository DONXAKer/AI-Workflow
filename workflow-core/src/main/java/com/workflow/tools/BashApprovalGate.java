package com.workflow.tools;

import com.workflow.api.RunWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Pauses execution when a Bash command is not in the allowlist and asks the operator
 * via WebSocket whether to allow it. The virtual thread blocks until the operator
 * responds or the 5-minute timeout expires (deny on timeout).
 */
@Component
public class BashApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(BashApprovalGate.class);
    private static final int TIMEOUT_SECONDS = 300;

    @Autowired(required = false)
    private RunWebSocketHandler wsHandler;

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    /** Keys are "{runId}:{blockId}" — commands for these are auto-approved without dialog. */
    private final java.util.Set<String> autoApprovedBlocks =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Sends a BASH_APPROVAL_REQUEST to the UI and blocks until the operator decides.
     * If the operator previously clicked "Allow all for block", returns {@code true} immediately.
     *
     * @return true if allowed, false if denied or timed out
     */
    public boolean requestApproval(UUID runId, String blockId, String command) {
        if (autoApprovedBlocks.contains(runId + ":" + blockId)) {
            log.info("Bash auto-approved (block-level): run={} block={} cmd={}", runId, blockId, command);
            return true;
        }
        if (wsHandler == null) return false;

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(requestId, future);

        wsHandler.sendBashApprovalRequest(runId, blockId, command, requestId);
        log.info("Bash approval requested: run={} block={} requestId={} cmd={}", runId, blockId, requestId, command);

        try {
            Boolean result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            pending.remove(requestId);
            log.warn("Bash approval timed out or interrupted for requestId={}", requestId);
            return false;
        }
    }

    public void resolve(String requestId, boolean approved) {
        CompletableFuture<Boolean> future = pending.remove(requestId);
        if (future != null) {
            future.complete(approved);
            log.info("Bash approval resolved: requestId={} approved={}", requestId, approved);
        } else {
            log.warn("No pending bash approval for requestId={}", requestId);
        }
    }

    /** Enables auto-approval of all subsequent bash commands for the given run+block. */
    public void autoApproveBlock(UUID runId, String blockId) {
        autoApprovedBlocks.add(runId + ":" + blockId);
        log.info("Bash auto-approve enabled for run={} block={}", runId, blockId);
    }
}
