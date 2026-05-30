package com.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.blocks.Block;
import com.workflow.config.BlockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-5 unit coverage for the two pure-ish remediation guards in {@link PipelineRunner}:
 * the global iteration envelope ({@code handleLoopback}) and {@code loop_history_json}
 * truncation ({@code capHistory}). Exercised via reflection — both are private and the
 * full happy-path is already covered by {@code PipelineRunnerLoopbackIT}.
 */
class PipelineRunnerRemediationTest {

    private PipelineRunner runner;
    private EscalationProperties escalationProperties;

    @BeforeEach
    void setUp() {
        runner = new PipelineRunner();
        escalationProperties = new EscalationProperties();
        ReflectionTestUtils.setField(runner, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(runner, "escalationProperties", escalationProperties);
        // noProgressDetector / metrics / runRepository left null — handleLoopback null-guards them.
    }

    private int invokeHandleLoopback(String loopKey, String targetId, String fromId, int maxIter,
                                     List<BlockConfig> blocks, int currentI, PipelineRun run) throws Exception {
        Method m = PipelineRunner.class.getDeclaredMethod("handleLoopback",
            String.class, String.class, String.class, int.class, List.class, Map.class,
            List.class, int.class, PipelineRun.class);
        m.setAccessible(true);
        return (int) m.invoke(runner, loopKey, targetId, fromId, maxIter,
            new ArrayList<>(List.of("issue-1")), new HashMap<>(), blocks, currentI, run);
    }

    private static BlockConfig blk(String id) {
        BlockConfig b = new BlockConfig();
        b.setId(id);
        return b;
    }

    @Test
    void globalCapAlreadyReached_stopsLoopback() throws Exception {
        escalationProperties.setMaxTotalIterations(2);
        PipelineRun run = new PipelineRun();
        run.setTotalRemediationIterations(2);                 // already at the global cap
        List<BlockConfig> blocks = List.of(blk("producer"), blk("verify"));

        int result = invokeHandleLoopback("loopback:verify:producer", "producer", "verify",
            5, blocks, 1, run);

        assertEquals(-1, result, "global cap reached → must stop even though local max (5) is not");
        assertEquals(2, run.getTotalRemediationIterations(), "must not increment past the cap");
    }

    @Test
    void belowGlobalCap_proceedsAndIncrements() throws Exception {
        escalationProperties.setMaxTotalIterations(6);
        PipelineRun run = new PipelineRun();
        List<BlockConfig> blocks = List.of(blk("producer"), blk("verify"));

        int result = invokeHandleLoopback("loopback:verify:producer", "producer", "verify",
            5, blocks, 1, run);

        assertEquals(0, result, "should rewind to the target block index (producer=0)");
        assertEquals(1, run.getTotalRemediationIterations(), "global counter must increment");
        assertEquals(Integer.valueOf(1), run.getLoopIterations().get("loopback:verify:producer"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveInjectContext_lenient_skipsUnresolvableRefAndKeepsGood() throws Exception {
        PipelineRun run = new PipelineRun();
        run.getOutputs().add(new BlockOutput(run, "verify", "{\"issues\":[\"x\"],\"score\":3}"));

        Map<String, String> inject = new HashMap<>();
        inject.put("feedback", "$.verify.issues");        // resolvable
        inject.put("missing", "$.ghost.field");            // unresolvable block — must not throw
        inject.put("absent", "$.verify.no_such_field");    // resolvable block, missing field
        inject.put("literal", "static text");              // non-ref passthrough

        Method m = PipelineRunner.class.getDeclaredMethod("resolveInjectContext", Map.class, PipelineRun.class);
        m.setAccessible(true);
        Map<String, Object> resolved = (Map<String, Object>) m.invoke(runner, inject, run);

        assertTrue(resolved.containsKey("feedback"), "resolvable ref must be present");
        assertEquals("static text", resolved.get("literal"), "non-ref value passes through");
        assertFalse(resolved.containsKey("missing"), "unresolvable block ref skipped (no crash)");
        assertFalse(resolved.containsKey("absent"), "missing field skipped (no crash)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void capHistory_trimsToMostRecentMax() throws Exception {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Map<String, Object> e = new HashMap<>();
            e.put("iteration", i);
            history.add(e);
        }
        Method m = PipelineRunner.class.getDeclaredMethod("capHistory", List.class);
        m.setAccessible(true);
        List<Map<String, Object>> capped = (List<Map<String, Object>>) m.invoke(runner, history);

        assertEquals(50, capped.size(), "history must be capped at LOOP_HISTORY_MAX");
        // Most-recent entries are retained (head trimmed).
        assertEquals(10, capped.get(0).get("iteration"));
        assertEquals(59, capped.get(capped.size() - 1).get("iteration"));
    }

    // ── PR-7: per-block timeout (opt-in) ──────────────────────────────────────

    private Map<String, Object> invokeRunBlock(Block block, BlockConfig cfg) throws Exception {
        Method m = PipelineRunner.class.getDeclaredMethod("runBlock",
            Block.class, Map.class, BlockConfig.class, PipelineRun.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(runner, block, new HashMap<>(), cfg, new PipelineRun());
    }

    private static Block sleepingBlock(long sleepMs) {
        return new Block() {
            @Override public String getName() { return "sleeper"; }
            @Override public String getDescription() { return "sleeps"; }
            @Override public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run)
                    throws Exception {
                Thread.sleep(sleepMs);
                return Map.of("done", true);
            }
        };
    }

    @Test
    void timeoutDisabled_slowBlockStillRuns() throws Exception {
        ReflectionTestUtils.setField(runner, "blockTimeoutEnabled", false);
        BlockConfig cfg = blk("slow");
        cfg.setTimeoutSeconds(1);                          // would trip, but feature is OFF
        Map<String, Object> out = invokeRunBlock(sleepingBlock(50), cfg);
        assertEquals(true, out.get("done"), "with timeout OFF the block must complete normally");
    }

    @Test
    void timeoutEnabled_underBudget_completes() throws Exception {
        ReflectionTestUtils.setField(runner, "blockTimeoutEnabled", true);
        BlockConfig cfg = blk("fast");
        cfg.setTimeoutSeconds(5);
        Map<String, Object> out = invokeRunBlock(sleepingBlock(20), cfg);
        assertEquals(true, out.get("done"));
    }

    @Test
    void timeoutEnabled_overBudget_throwsTimeout() {
        ReflectionTestUtils.setField(runner, "blockTimeoutEnabled", true);
        BlockConfig cfg = blk("slow");
        cfg.setTimeoutSeconds(1);
        // 2s sleep vs 1s budget → must throw (wrapped TimeoutException), not hang.
        Exception ex = assertThrows(Exception.class, () -> invokeRunBlock(sleepingBlock(2000), cfg));
        Throwable root = ex.getCause() != null ? ex.getCause() : ex;  // reflection wraps in InvocationTargetException
        assertTrue(root.getMessage() != null && root.getMessage().contains("timed out"),
            () -> "expected a timeout message, got: " + root);
    }
}
