package com.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.EscalationConfig;
import com.workflow.config.EscalationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ladder-walking semantics of {@link EscalationService}:
 * cloud-step retries, transition to human-step, exhaustion, state persistence.
 */
class EscalationServiceTest {

    private EscalationService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        EscalationProperties props = new EscalationProperties();
        EscalationResolver resolver = new EscalationResolver(props, mapper);
        service = new EscalationService(resolver, mapper, null);
    }

    private static PipelineRun newRun() {
        PipelineRun run = new PipelineRun();
        run.setId(UUID.randomUUID());
        run.setPipelineName("test");
        run.setStatus(RunStatus.RUNNING);
        return run;
    }

    @Test
    void emptyLadder_returnsExhausted() {
        PipelineRun run = newRun();
        EscalationDecision d = service.attemptEscalation(run, "verify", "codegen", List.of(), Map.of());
        assertInstanceOf(EscalationDecision.Exhausted.class, d);
    }

    @Test
    void cloudStep_firstAttempt_returnsRetryWithCloud_andRecordsOverride() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 2)
        );

        EscalationDecision d = service.attemptEscalation(run, "verify", "codegen", ladder,
                Map.of("issues", List.of("err1")));

        assertInstanceOf(EscalationDecision.RetryWithCloud.class, d);
        EscalationDecision.RetryWithCloud retry = (EscalationDecision.RetryWithCloud) d;
        assertEquals("codegen", retry.targetBlockId());
        assertEquals("openrouter", retry.override().provider());
        assertEquals("smart", retry.override().model());

        // Override is persisted on the run
        var stored = service.getOverride(run, "codegen");
        assertTrue(stored.isPresent());
        assertEquals("smart", stored.get().model());
    }

    @Test
    void cloudStep_secondAttempt_advancesAttemptCounter() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 3)
        );

        service.attemptEscalation(run, "verify", "codegen", ladder, Map.of());
        service.attemptEscalation(run, "verify", "codegen", ladder, Map.of());
        EscalationDecision third = service.attemptEscalation(run, "verify", "codegen", ladder, Map.of());

        // 3 attempts allowed, all three should be RetryWithCloud
        assertInstanceOf(EscalationDecision.RetryWithCloud.class, third);
    }

    @Test
    void cloudStep_exhausted_advancesToNextStep() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 2),
                new EscalationStep.Human(List.of("ui"), 60)
        );

        service.attemptEscalation(run, "verify", "codegen", ladder, Map.of());  // cloud try 1
        service.attemptEscalation(run, "verify", "codegen", ladder, Map.of());  // cloud try 2
        EscalationDecision next = service.attemptEscalation(run, "verify", "codegen", ladder, Map.of());

        // After 2 cloud attempts (== max_iterations), should advance to human step
        assertInstanceOf(EscalationDecision.PauseForHuman.class, next);
    }

    @Test
    void humanStep_returnsPauseForHuman_withBundle() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Human(List.of("email", "ui"), 3600L)
        );

        EscalationDecision d = service.attemptEscalation(run, "verify", "codegen", ladder,
                Map.of("issues", List.of("connection-reset")));

        assertInstanceOf(EscalationDecision.PauseForHuman.class, d);
        EscalationDecision.PauseForHuman pause = (EscalationDecision.PauseForHuman) d;
        assertEquals("verify", pause.failingBlockId());
        assertEquals("codegen", pause.targetBlockId());
        assertEquals(3600L, pause.step().timeoutSeconds());
        assertEquals("max_iterations_exhausted", pause.bundle().get("reason"));
        assertEquals("verify", pause.bundle().get("failing_block_id"));
        assertNotNull(pause.bundle().get("last_failure"));
    }

    @Test
    void humanStep_afterUsed_nextCallReturnsExhausted() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Human(List.of("ui"), 60L)
        );

        EscalationDecision first = service.attemptEscalation(run, "v", "t", ladder, Map.of());
        EscalationDecision second = service.attemptEscalation(run, "v", "t", ladder, Map.of());

        assertInstanceOf(EscalationDecision.PauseForHuman.class, first);
        assertInstanceOf(EscalationDecision.Exhausted.class, second);
    }

    @Test
    void fullLadder_cloudThenHuman_walksCorrectly() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 1),
                new EscalationStep.Cloud("openrouter", "reasoning", 1),
                new EscalationStep.Human(List.of("ui"), 60L)
        );

        EscalationDecision d1 = service.attemptEscalation(run, "v", "t", ladder, Map.of());
        EscalationDecision d2 = service.attemptEscalation(run, "v", "t", ladder, Map.of());
        EscalationDecision d3 = service.attemptEscalation(run, "v", "t", ladder, Map.of());
        EscalationDecision d4 = service.attemptEscalation(run, "v", "t", ladder, Map.of());

        // cloud-smart (1 attempt) → cloud-reasoning (1 attempt) → human → exhausted
        assertInstanceOf(EscalationDecision.RetryWithCloud.class, d1);
        assertEquals("smart", ((EscalationDecision.RetryWithCloud) d1).override().model());

        assertInstanceOf(EscalationDecision.RetryWithCloud.class, d2);
        assertEquals("reasoning", ((EscalationDecision.RetryWithCloud) d2).override().model());

        assertInstanceOf(EscalationDecision.PauseForHuman.class, d3);
        assertInstanceOf(EscalationDecision.Exhausted.class, d4);
    }

    @Test
    void applyRuntimeOverride_swapsModelOnAgentConfig() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 2)
        );
        service.attemptEscalation(run, "v", "codegen", ladder, Map.of());

        var blockConfig = new com.workflow.config.BlockConfig();
        blockConfig.setId("codegen");
        var agent = new com.workflow.config.AgentConfig();
        agent.setModel("Qwen/Qwen3-4B-AWQ");
        blockConfig.setAgent(agent);

        var result = service.applyRuntimeOverride(blockConfig, run);
        assertEquals("smart", result.getAgent().getModel(),
                "applyRuntimeOverride must swap the model when an override is registered");
    }

    @Test
    void applyRuntimeOverride_noOverride_returnsConfigUnchanged() {
        PipelineRun run = newRun();
        var blockConfig = new com.workflow.config.BlockConfig();
        blockConfig.setId("codegen");
        var agent = new com.workflow.config.AgentConfig();
        agent.setModel("Qwen/Qwen3-4B-AWQ");
        blockConfig.setAgent(agent);

        var result = service.applyRuntimeOverride(blockConfig, run);
        assertEquals("Qwen/Qwen3-4B-AWQ", result.getAgent().getModel());
    }

    @Test
    void effectiveProvider_withOverride_returnsOverriddenProvider() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 2)
        );
        service.attemptEscalation(run, "v", "codegen", ladder, Map.of());

        var provider = service.effectiveProvider(run, "codegen",
                com.workflow.llm.LlmProvider.OLLAMA);
        assertEquals(com.workflow.llm.LlmProvider.OPENROUTER, provider);
    }

    @Test
    void effectiveProvider_noOverride_returnsFallback() {
        PipelineRun run = newRun();
        var provider = service.effectiveProvider(run, "codegen",
                com.workflow.llm.LlmProvider.OLLAMA);
        assertEquals(com.workflow.llm.LlmProvider.OLLAMA, provider);
    }

    @Test
    void clearOverrides_emptiesTheMap() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 2)
        );
        service.attemptEscalation(run, "v", "codegen", ladder, Map.of());
        assertTrue(service.getOverride(run, "codegen").isPresent());

        service.clearOverrides(run);
        assertTrue(service.getOverride(run, "codegen").isEmpty());
    }

    @Test
    void perBlockStateIsolation_overrideForOneBlockDoesntAffectAnother() {
        PipelineRun run = newRun();
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 2)
        );

        service.attemptEscalation(run, "verify_a", "codegen_a", ladder, Map.of());

        assertTrue(service.getOverride(run, "codegen_a").isPresent());
        assertTrue(service.getOverride(run, "codegen_b").isEmpty(),
                "Override for codegen_a must not leak to codegen_b");
    }

    @Test
    void corruptStateJson_resetsToInitialAndProceeds() {
        PipelineRun run = newRun();
        run.setEscalationStateJson("not-valid-json{");
        var ladder = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "smart", 2)
        );

        // Should not throw — corrupt state silently resets
        EscalationDecision d = service.attemptEscalation(run, "v", "t", ladder, Map.of());
        assertInstanceOf(EscalationDecision.RetryWithCloud.class, d);
    }
}
