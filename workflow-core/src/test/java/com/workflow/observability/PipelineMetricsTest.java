package com.workflow.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-5: the loop-control / remediation counters must register and increment so operators
 * can finally see loopback churn, no-progress short-circuits, and escalation steps in
 * {@code /actuator/prometheus}.
 */
class PipelineMetricsTest {

    private SimpleMeterRegistry registry;
    private PipelineMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PipelineMetrics();
        ReflectionTestUtils.setField(metrics, "registry", registry);
    }

    @Test
    void loopbackIteration_incrementsTaggedCounter() {
        metrics.recordLoopbackIteration("verify", "codegen");
        metrics.recordLoopbackIteration("verify", "codegen");
        double count = registry.get("workflow_loopback_iterations_total")
            .tag("from", "verify").tag("to", "codegen").counter().count();
        assertEquals(2.0, count);
    }

    @Test
    void noProgressDetection_incrementsCounter() {
        metrics.recordNoProgressDetection("verify");
        assertEquals(1.0, registry.get("workflow_no_progress_detections_total")
            .tag("block", "verify").counter().count());
    }

    @Test
    void escalationStep_incrementsPerTier() {
        metrics.recordEscalationStep("cloud");
        metrics.recordEscalationStep("human");
        assertEquals(1.0, registry.get("workflow_escalation_steps_total")
            .tag("tier", "cloud").counter().count());
        assertEquals(1.0, registry.get("workflow_escalation_steps_total")
            .tag("tier", "human").counter().count());
    }

    @Test
    void nullLabels_doNotThrow() {
        assertDoesNotThrow(() -> {
            metrics.recordLoopbackIteration(null, null);
            metrics.recordNoProgressDetection(null);
            metrics.recordEscalationStep(null);
        });
    }
}
