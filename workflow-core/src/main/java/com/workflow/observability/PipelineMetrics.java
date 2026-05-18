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
