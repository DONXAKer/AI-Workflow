package com.workflow.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RunWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RunWebSocketHandler.class);

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Broadcast a lightweight {@code RUN_STARTED} event to the global topic so that
     * subscribers such as {@code RunsContext} can update the active-runs badge without
     * waiting for the {@code RUN_COMPLETE} event.  Called once when a new run begins.
     */
    public void sendRunStarted(UUID runId) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "RUN_STARTED");
        message.put("runId", runId.toString());
        broadcastGlobal(message);
    }

    public void sendBlockStarted(UUID runId, String blockId) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "BLOCK_STARTED");
        message.put("blockId", blockId);
        message.put("status", "running");
        message.put("runId", runId.toString());
        send(runId, message);
        broadcastGlobal(message);
    }

    public void sendBlockComplete(UUID runId, String blockId, Map<String, Object> output) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "BLOCK_COMPLETE");
        message.put("blockId", blockId);
        message.put("status", "completed");
        message.put("output", output);
        message.put("runId", runId.toString());
        send(runId, message);
    }

    public void sendApprovalRequest(UUID runId, String blockId, String description, Map<String, Object> output) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "APPROVAL_REQUEST");
        message.put("blockId", blockId);
        message.put("description", description);
        message.put("output", output);
        message.put("status", "awaiting_approval");
        message.put("runId", runId.toString());
        send(runId, message);
        // Broadcast lightweight signal (no output) so the active-runs list updates in real-time
        Map<String, Object> globalSignal = new HashMap<>();
        globalSignal.put("type", "APPROVAL_REQUEST");
        globalSignal.put("blockId", blockId);
        globalSignal.put("status", "awaiting_approval");
        globalSignal.put("runId", runId.toString());
        broadcastGlobal(globalSignal);
    }

    public void sendBashApprovalRequest(UUID runId, String blockId, String command, String requestId) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "BASH_APPROVAL_REQUEST");
        message.put("runId", runId.toString());
        message.put("blockId", blockId);
        message.put("command", command);
        message.put("requestId", requestId);
        send(runId, message);
    }

    /**
     * Non-blocking notification for blocks running in {@code auto_notify} mode.
     * The block is already committed and the pipeline moves on; this event lets
     * subscribers display the output and optionally trigger a rollback window.
     */
    public void sendAutoNotify(UUID runId, String blockId, String description, Map<String, Object> output) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "AUTO_NOTIFY");
        message.put("blockId", blockId);
        message.put("description", description);
        message.put("output", output);
        message.put("status", "auto_approved");
        message.put("runId", runId.toString());
        send(runId, message);
        broadcastGlobal(message);
    }

    public void sendBlockSkipped(UUID runId, String blockId, String reason) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "BLOCK_SKIPPED");
        message.put("blockId", blockId);
        message.put("status", "skipped");
        message.put("reason", reason);
        message.put("runId", runId.toString());
        send(runId, message);
    }

    public void sendBlockProgress(UUID runId, String blockId, String detail) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "BLOCK_PROGRESS");
        message.put("blockId", blockId);
        message.put("detail", detail);
        message.put("runId", runId.toString());
        send(runId, message);
    }

    public void sendRunComplete(UUID runId, String status) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "RUN_COMPLETE");
        message.put("status", status);
        message.put("runId", runId.toString());
        send(runId, message);
        broadcastGlobal(message);
    }

    private void send(UUID runId, Map<String, Object> message) {
        try {
            messagingTemplate.convertAndSend("/topic/runs/" + runId, message);
        } catch (Exception e) {
            log.warn("Failed to send WebSocket message for run {}: {}", runId, e.getMessage());
        }
    }

    private void broadcastGlobal(Map<String, Object> message) {
        try {
            messagingTemplate.convertAndSend("/topic/runs", message);
        } catch (Exception e) {
            log.warn("Failed to broadcast global WebSocket message: {}", e.getMessage());
        }
    }
}
