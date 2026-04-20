package com.workflow.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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

    @Override
    public ApprovalResult request(String blockId, String blockType, String description,
                                  Map<String, Object> inputData, Map<String, Object> outputData,
                                  List<String> remainingBlockIds) {
        CompletableFuture<ApprovalResult> future = new CompletableFuture<>();
        pendingApprovals.put(blockId, future);

        Map<String, Object> message = new HashMap<>();
        message.put("type", "APPROVAL_REQUEST");
        message.put("blockId", blockId);
        message.put("blockType", blockType);
        message.put("description", description);
        message.put("inputData", inputData);
        message.put("outputData", outputData);
        message.put("remainingBlockIds", remainingBlockIds);

        try {
            messagingTemplate.convertAndSend("/topic/runs/" + blockId, message);
            log.info("Sent approval request for block: {}", blockId);

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

    public void resolveApproval(String blockId, ApprovalResult result) {
        CompletableFuture<ApprovalResult> future = pendingApprovals.remove(blockId);
        if (future != null) {
            future.complete(result);
            log.info("Resolved approval for block: {} with status: {}", blockId, result.getStatus());
        } else {
            log.warn("No pending approval found for block: {}", blockId);
        }
    }
}
