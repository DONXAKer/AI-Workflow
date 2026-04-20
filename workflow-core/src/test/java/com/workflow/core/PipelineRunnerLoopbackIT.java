package com.workflow.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.config.FieldCheckConfig;
import com.workflow.config.OnFailConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.VerifyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of {@link PipelineRunner#handleLoopback} via real Spring context,
 * real H2, real {@link com.workflow.blocks.VerifyBlock}, and a test-only
 * {@link LoopbackProducerBlock} that changes its output based on whether
 * {@code _loopback} was injected by the runner.
 *
 * <p>Closes the gap flagged in the Phase 1 post-M5 review: existing tests only
 * exercise the block-level {@code _loopback} handling (e.g.
 * {@code AgentWithToolsBlockTest.loopbackFeedbackAppendedToUserMessage}), not the
 * full runner path that rewrites {@code completedBlocks}, persists
 * {@code _loopback_<target>} output, and rewinds the execution pointer.
 *
 * <p>No OpenRouter — the pipeline uses only the producer + verify blocks. Runs in
 * under a second once the Spring context is up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:loopback-it;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "workflow.mode=cli"
})
@Tag("integration")
class PipelineRunnerLoopbackIT {

    @Autowired private PipelineRunner runner;
    @Autowired private PipelineRunRepository runRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void verifyFailureTriggersLoopbackThenSucceeds() throws Exception {
        PipelineConfig config = buildLoopbackConfig(3);
        UUID runId = UUID.randomUUID();

        runner.run(config, "loopback-smoke", runId).get(30, TimeUnit.SECONDS);

        RunSnapshot snap = readRun(runId);
        assertEquals(RunStatus.COMPLETED, snap.status,
            "run should finish COMPLETED after one loopback retry, got " + snap.status);

        // One loopback iteration recorded under the canonical key.
        String loopKey = "loopback:verify_value:producer";
        assertEquals(Integer.valueOf(1), snap.iterations.get(loopKey),
            "expected exactly one loopback iteration, got " + snap.iterations);

        // The producer block ran twice and its *final* persisted output is the
        // retry value (BlockOutputs are replaced in place when the runner rewinds).
        Map<String, Object> producerOut = snap.outputs.get("producer");
        assertEquals("good", producerOut.get("value"));
        assertEquals(true, producerOut.get("retry"));

        // The synthetic _loopback_<target> record carries issues + iteration.
        Map<String, Object> loopbackRecord = snap.outputs.get("_loopback_producer");
        assertNotNull(loopbackRecord);
        assertEquals(1, ((Number) loopbackRecord.get("iteration")).intValue());
        Object issues = loopbackRecord.get("issues");
        assertTrue(issues instanceof List<?>);
        assertFalse(((List<?>) issues).isEmpty(),
            "_loopback record should carry the verify issues");

        // The producer observed the _loopback on retry — proves PipelineRunner
        // injected it into the input map, not just saved the sidecar output.
        assertEquals(1, ((Number) producerOut.get("received_loopback_iteration")).intValue());
        assertFalse(((List<?>) producerOut.get("received_loopback_issues")).isEmpty());

        assertNotNull(snap.loopHistoryJson);
        List<Map<String, Object>> history = objectMapper.readValue(
            snap.loopHistoryJson, new TypeReference<>() {});
        assertEquals(1, history.size(), "loop_history should have one entry");
        assertEquals("verify_value", history.get(0).get("from_block"));
        assertEquals("producer", history.get(0).get("to_block"));
    }

    @Test
    void maxIterationsStopsLoopWithFailure() throws Exception {
        // max_iterations=0 means the first verify failure exhausts the budget
        // and the runner marks the run FAILED instead of retrying.
        PipelineConfig config = buildLoopbackConfig(0);
        UUID runId = UUID.randomUUID();

        try {
            runner.run(config, "exhaust", runId).get(30, TimeUnit.SECONDS);
            fail("runner should have failed once loopback budget exhausted");
        } catch (Exception expected) {
            // RuntimeException from handleLoopback returning -1 path
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        RunStatus lastStatus = null;
        while (Instant.now().isBefore(deadline)) {
            lastStatus = readRunStatus(runId);
            if (lastStatus == RunStatus.FAILED) break;
            Thread.sleep(50);
        }
        assertEquals(RunStatus.FAILED, lastStatus,
            "budget exhaustion should mark the run FAILED");
    }

    private PipelineConfig buildLoopbackConfig(int maxIterations) {
        PipelineConfig config = new PipelineConfig();
        config.setName("loopback-smoke");

        BlockConfig producer = new BlockConfig();
        producer.setId("producer");
        producer.setBlock("loopback_producer");
        producer.setApproval(false);

        BlockConfig verify = new BlockConfig();
        verify.setId("verify_value");
        verify.setBlock("verify");
        verify.setApproval(false);
        verify.setDependsOn(List.of("producer"));

        FieldCheckConfig check = new FieldCheckConfig();
        check.setField("value");
        check.setRule("equals");
        check.setValue("good");
        check.setMessage("value is not 'good'");

        OnFailConfig onFail = new OnFailConfig();
        onFail.setAction("loopback");
        onFail.setTarget("producer");
        onFail.setMaxIterations(maxIterations);

        VerifyConfig vc = new VerifyConfig();
        vc.setSubject("producer");
        vc.setChecks(List.of(check));
        vc.setOnFail(onFail);
        verify.setVerify(vc);

        List<BlockConfig> blocks = new ArrayList<>();
        blocks.add(producer);
        blocks.add(verify);
        config.setPipeline(blocks);
        return config;
    }

    /**
     * Loads every field we assert on inside one transactional scope and hands back
     * an eager snapshot — Hibernate's LAZY collections on PipelineRun would blow up
     * the moment an assertion touches them from the test thread otherwise.
     */
    private RunSnapshot readRun(UUID runId) {
        return transactionTemplate.execute(tx -> {
            PipelineRun run = runRepository.findById(runId)
                .orElseThrow(() -> new AssertionError("run " + runId + " not persisted"));
            RunSnapshot snap = new RunSnapshot();
            snap.status = run.getStatus();
            snap.iterations = new HashMap<>(run.getLoopIterations());
            snap.loopHistoryJson = run.getLoopHistoryJson();
            snap.outputs = new LinkedHashMap<>();
            for (BlockOutput bo : run.getOutputs()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = objectMapper.readValue(bo.getOutputJson(),
                        new TypeReference<Map<String, Object>>() {});
                    snap.outputs.put(bo.getBlockId(), map);
                } catch (Exception e) {
                    throw new RuntimeException("output parse failure for "
                        + bo.getBlockId() + ": " + e.getMessage(), e);
                }
            }
            return snap;
        });
    }

    private RunStatus readRunStatus(UUID runId) {
        return transactionTemplate.execute(tx ->
            runRepository.findById(runId).map(PipelineRun::getStatus).orElse(null));
    }

    /** Flat, detached view of a PipelineRun for assertion code. */
    private static class RunSnapshot {
        RunStatus status;
        Map<String, Integer> iterations;
        String loopHistoryJson;
        Map<String, Map<String, Object>> outputs;
    }
}
