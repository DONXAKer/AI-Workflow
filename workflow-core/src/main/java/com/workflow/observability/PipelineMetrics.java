package com.workflow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    public void recordRunStarted() {
        runCounter("started").increment();
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
