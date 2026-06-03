package com.workflow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus metrics for pipeline runs, block execution, LLM calls, and integrations.
 *
 * <p>Exposed via {@code /actuator/prometheus} (ADMIN-only in Spring Security config).
 */
@Component
public class PipelineMetrics {

    @Autowired
    private MeterRegistry registry;

    private final ConcurrentMap<String, Counter> runCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> stageCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> integrationErrorCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> llmTokensCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> stageTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> loopbackCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> noProgressCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> escalationStepCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> preflightBlockCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> preflightWarnCounters = new ConcurrentHashMap<>();

    /**
     * Per-resolution timer of approval wait time. Tag {@code status} ∈
     * {APPROVED, REJECTED, EDIT, SKIP, JUMP, TIMEOUT_FAIL, TIMEOUT_NOTIFY, TIMEOUT_APPROVE,
     * TIMEOUT_ESCALATE}. Used to alert on stuck approvals (e.g. p95 &gt; 30 min).
     */
    private final ConcurrentMap<String, Timer> approvalWaitTimers = new ConcurrentHashMap<>();

    /** Live gauge of approvals currently in PAUSED_FOR_APPROVAL. */
    private final AtomicLong approvalsPending = new AtomicLong(0);

    @PostConstruct
    void registerGauges() {
        Gauge.builder("workflow_approvals_pending", approvalsPending, AtomicLong::doubleValue)
            .description("Approvals currently awaiting operator decision")
            .register(registry);
    }

    public void recordRunStarted() {
        runCounter("started").increment();
    }

    /** Increment when a run pauses for approval (and at startup recovery). */
    public void approvalPaused() {
        approvalsPending.incrementAndGet();
    }

    /** Records the wait time for an approval that finished, and decrements the pending gauge. */
    public void approvalResolved(String status, Duration waited) {
        approvalsPending.updateAndGet(v -> Math.max(0, v - 1));
        approvalWaitTimers.computeIfAbsent(status,
            k -> Timer.builder("workflow_approval_wait_seconds")
                .description("Time from pause-for-approval to operator/timeout resolution")
                .tag("status", k)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
        ).record(waited);
    }

    public void recordRunComplete(String status) {
        runCounter(status.toLowerCase()).increment();
    }

    public void recordBlockStarted(String blockType) {
        stageCounter(blockType, "started").increment();
    }

    public void recordBlockCompleted(String blockType, Duration elapsed) {
        stageCounter(blockType, "completed").increment();
        stageTimers.computeIfAbsent(blockType,
            k -> Timer.builder("workflow_stage_duration_seconds")
                .tag("stage", k)
                .register(registry)
        ).record(elapsed);
    }

    public void recordBlockFailed(String blockType) {
        stageCounter(blockType, "failed").increment();
    }

    public void recordIntegrationError(String integration) {
        integrationErrorCounters.computeIfAbsent(integration,
            k -> Counter.builder("workflow_integration_errors_total")
                .tag("integration", k)
                .register(registry)
        ).increment();
    }

    public void recordLlmTokens(String model, long tokens) {
        llmTokensCounters.computeIfAbsent(model,
            k -> Counter.builder("workflow_llm_tokens_total")
                .tag("model", k)
                .register(registry)
        ).increment(tokens);
    }

    /**
     * Loop-control & remediation observability (PR-5). Previously these mechanisms were
     * invisible in metrics — operators couldn't see token waste from repeated loopbacks,
     * how often the no-progress detector fired, or whether escalation kicked in.
     */
    public void recordLoopbackIteration(String fromBlock, String toBlock) {
        loopbackCounters.computeIfAbsent(fromBlock + "->" + toBlock,
            k -> Counter.builder("workflow_loopback_iterations_total")
                .tag("from", fromBlock != null ? fromBlock : "?")
                .tag("to", toBlock != null ? toBlock : "?")
                .register(registry)
        ).increment();
    }

    public void recordNoProgressDetection(String block) {
        noProgressCounters.computeIfAbsent(block != null ? block : "?",
            k -> Counter.builder("workflow_no_progress_detections_total")
                .tag("block", k)
                .register(registry)
        ).increment();
    }

    public void recordEscalationStep(String tier) {
        escalationStepCounters.computeIfAbsent(tier != null ? tier : "?",
            k -> Counter.builder("workflow_escalation_steps_total")
                .tag("tier", k)
                .register(registry)
        ).increment();
    }

    /**
     * Pre-run diagnostics gate (com.workflow.preflight). A "block" is a hard-fail that prevented a
     * run from starting; a "warning" is an advisory (reachability/inconclusive/conditional) that did
     * not block. Tag {@code requirement} ∈ {WORKING_DIR, BINARY, PROVIDER, INTEGRATION, GIT_REPO}.
     */
    public void recordPreflightBlock(String requirement) {
        preflightBlockCounters.computeIfAbsent(requirement != null ? requirement : "?",
            k -> Counter.builder("workflow_preflight_blocks_total")
                .tag("requirement", k)
                .register(registry)
        ).increment();
    }

    public void recordPreflightWarning(String requirement) {
        preflightWarnCounters.computeIfAbsent(requirement != null ? requirement : "?",
            k -> Counter.builder("workflow_preflight_warnings_total")
                .tag("requirement", k)
                .register(registry)
        ).increment();
    }

    private Counter runCounter(String status) {
        return runCounters.computeIfAbsent(status,
            k -> Counter.builder("workflow_runs_total")
                .tag("status", k)
                .register(registry));
    }

    private Counter stageCounter(String blockType, String status) {
        return stageCounters.computeIfAbsent(blockType + ":" + status,
            k -> Counter.builder("workflow_blocks_total")
                .tag("stage", blockType)
                .tag("status", status)
                .register(registry));
    }
}
