package com.workflow.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "workflow.mode", havingValue = "gui")
public class WebSocketApprovalGate implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(WebSocketApprovalGate.class);

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, CompletableFuture<ApprovalResult>> pendingApprovals =
        new ConcurrentHashMap<>();

    /**
     * Pre-stored results for cases where the pipeline thread died (e.g. server restart)
     * but the run is still in PAUSED_FOR_APPROVAL. The controller stores the result here
     * before resuming, so the resumed request() call returns immediately.
     */
    private final ConcurrentHashMap<String, ApprovalResult> preApprovedResults =
        new ConcurrentHashMap<>();

    @Override
    public ApprovalResult request(String blockId, String blockType, String description,
                                  Map<String, Object> inputData, Map<String, Object> outputData,
                                  List<String> remainingBlockIds) {
        // Fast path: result was pre-stored by the controller during a "stale approval" recovery
        ApprovalResult preApproved = preApprovedResults.remove(blockId);
        if (preApproved != null) {
            log.info("Using pre-approved result for block: {}", blockId);
            return preApproved;
        }

        CompletableFuture<ApprovalResult> future = new CompletableFuture<>();
        pendingApprovals.put(blockId, future);

        try {
            // Wait up to 1 hour for approval
            ApprovalResult result = future.get(3600, TimeUnit.SECONDS);
            return result;

        } catch (TimeoutException e) {
            pendingApprovals.remove(blockId);
            throw new PipelineRejectedException("Approval timeout for block: " + blockId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingApprovals.remove(blockId);
            throw new PipelineRejectedException("Approval interrupted for block: " + blockId);
        } catch (Exception e) {
            pendingApprovals.remove(blockId);
            throw new RuntimeException("Approval gate error for block: " + blockId, e);
        }
    }

    /**
     * @return true if a live future was found and completed; false if no pending approval existed
     *         (e.g. after a server restart — caller should handle via preApprove + resume).
     */
    public boolean resolveApproval(String blockId, ApprovalResult result) {
        CompletableFuture<ApprovalResult> future = pendingApprovals.remove(blockId);
        if (future != null) {
            future.complete(result);
            log.info("Resolved approval for block: {} with status: {}", blockId, result.getStatus());
            return true;
        } else {
            log.warn("No pending approval found for block: {} (server restart?)", blockId);
            return false;
        }
    }

    /** Pre-stores a result so the next request() call for blockId returns it immediately. */
    public void preApprove(String blockId, ApprovalResult result) {
        preApprovedResults.put(blockId, result);
        log.info("Pre-approved result stored for block: {}", blockId);
    }
}
