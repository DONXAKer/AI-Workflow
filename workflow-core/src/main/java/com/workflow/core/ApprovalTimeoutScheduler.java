package com.workflow.core;

import com.workflow.api.RunWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodically scans runs paused for approval and applies the configured {@code on_timeout}
 * action once {@code block.timeout} seconds have elapsed since pause.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code fail} — transitions the run to FAILED with a timeout error, releases the waiter.</li>
 *   <li>{@code notify} — emits a non-blocking WebSocket notification; the run stays paused.</li>
 *   <li>{@code escalate} — notify + annotate with target role (consumed by Epic 2.2 once roles land).</li>
 *   <li>{@code approve} — automatically approves the paused block and resumes the pipeline.</li>
 * </ul>
 *
 * <p>Notify/escalate are idempotent — we mark the timeout as "fired" by clearing
 * {@code approvalTimeoutSeconds} so subsequent scans skip the run. An operator
 * resolving or returning the run clears the paused fields via the normal code path.
 */
@Component
public class ApprovalTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutScheduler.class);

    @Autowired
    private PipelineRunRepository runRepository;

    @Autowired(required = false)
    private WebSocketApprovalGate webSocketApprovalGate;

    @Autowired(required = false)
    private RunWebSocketHandler wsHandler;

    /** Runs every 60 seconds. First tick delayed 30 seconds to let the app settle. */
    @Scheduled(initialDelayString = "30000", fixedRateString = "60000")
    public void checkTimeouts() {
        List<PipelineRun> paused = runRepository
            .findByStatusAndPausedAtIsNotNullAndApprovalTimeoutSecondsIsNotNull(RunStatus.PAUSED_FOR_APPROVAL);
        if (paused.isEmpty()) return;

        Instant now = Instant.now();
        for (PipelineRun run : paused) {
            try {
                handleOne(run, now);
            } catch (Exception e) {
                log.error("Failed to process timeout for run {}: {}", run.getId(), e.getMessage(), e);
            }
        }
    }

    private void handleOne(PipelineRun run, Instant now) {
        Instant pausedAt = run.getPausedAt();
        Integer timeoutSeconds = run.getApprovalTimeoutSeconds();
        if (pausedAt == null || timeoutSeconds == null || timeoutSeconds <= 0) return;

        Duration elapsed = Duration.between(pausedAt, now);
        if (elapsed.getSeconds() < timeoutSeconds) return;

        String action = run.getApprovalTimeoutAction() != null ? run.getApprovalTimeoutAction() : "notify";
        String blockId = run.getCurrentBlock();
        log.warn("Approval timeout fired: run={}, block={}, elapsed={}s, limit={}s, action={}",
            run.getId(), blockId, elapsed.getSeconds(), timeoutSeconds, action);

        switch (action) {
            case "fail" -> applyFail(run, blockId, elapsed);
            case "approve" -> applyApprove(run, blockId, elapsed);
            case "escalate" -> applyEscalate(run, blockId, elapsed);
            default -> applyNotify(run, blockId, elapsed);
        }
    }

    private void applyFail(PipelineRun run, String blockId, Duration elapsed) {
        String error = String.format("Approval timeout on block '%s' after %ds", blockId, elapsed.getSeconds());
        run.setStatus(RunStatus.FAILED);
        run.setError(error);
        run.setCompletedAt(Instant.now());
        run.setPausedAt(null);
        run.setApprovalTimeoutSeconds(null);
        run.setApprovalTimeoutAction(null);
        runRepository.save(run);
        // Release the blocked approval gate thread so the execution future completes.
        if (webSocketApprovalGate != null && blockId != null) {
            webSocketApprovalGate.resolveApproval(blockId,
                ApprovalResult.builder().status("REJECTED").output(new HashMap<>()).build());
        }
        if (wsHandler != null) wsHandler.sendRunComplete(run.getId(), "FAILED");
    }

    private void applyNotify(PipelineRun run, String blockId, Duration elapsed) {
        if (wsHandler != null && blockId != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("elapsedSeconds", elapsed.getSeconds());
            payload.put("limitSeconds", run.getApprovalTimeoutSeconds());
            payload.put("action", "notify");
            wsHandler.sendAutoNotify(run.getId(), blockId, "Approval timeout exceeded", payload);
        }
        // Mark as fired — prevent repeated notifications every 60s for the same stale run.
        run.setApprovalTimeoutSeconds(null);
        runRepository.save(run);
    }

    private void applyApprove(PipelineRun run, String blockId, Duration elapsed) {
        log.info("Auto-approving block '{}' for run {} after timeout ({}s elapsed)",
            blockId, run.getId(), elapsed.getSeconds());
        run.setPausedAt(null);
        run.setApprovalTimeoutSeconds(null);
        run.setApprovalTimeoutAction(null);
        runRepository.save(run);
        if (webSocketApprovalGate != null && blockId != null) {
            webSocketApprovalGate.resolveApproval(blockId,
                ApprovalResult.builder().status("APPROVED").output(new HashMap<>()).skipFuture(false).build());
        }
        if (wsHandler != null) wsHandler.sendAutoNotify(run.getId(), blockId,
            "Block auto-approved after timeout", Map.of("elapsedSeconds", elapsed.getSeconds()));
    }

    private void applyEscalate(PipelineRun run, String blockId, Duration elapsed) {
        // Targeted notification to a role will land with Epic 2.2 (Roles). For now, notify + log.
        log.warn("Escalation requested for run {} — target role resolution pending Epic 2.2", run.getId());
        applyNotify(run, blockId, elapsed);
    }
}
