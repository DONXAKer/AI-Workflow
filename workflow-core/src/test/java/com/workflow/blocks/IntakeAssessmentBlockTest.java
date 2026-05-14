package com.workflow.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the LLM-response parsing, bucket-override logic, and fallback paths.
 * LlmClient is mocked — these tests document the contract without a network call.
 */
class IntakeAssessmentBlockTest {

    private IntakeAssessmentBlock block;
    private LlmClient mockLlm;

    @BeforeEach
    void setUp() {
        block = new IntakeAssessmentBlock();
        mockLlm = mock(LlmClient.class);
        ReflectionTestUtils.setField(block, "llmClient", mockLlm);
        ReflectionTestUtils.setField(block, "objectMapper", new ObjectMapper());
    }

    private PipelineRun runWith(String requirement) {
        PipelineRun r = new PipelineRun();
        r.setPipelineName("test");
        r.setRequirement(requirement);
        return r;
    }

    private BlockConfig blockConfig() {
        BlockConfig cfg = new BlockConfig();
        cfg.setId("intake_assessment");
        cfg.setBlock("intake_assessment");
        return cfg;
    }

    // ── bucketFor: static logic — exhaustive at boundaries ──

    @Test
    void bucketFor_below60_isClarify() {
        assertEquals("clarify", IntakeAssessmentBlock.bucketFor(0));
        assertEquals("clarify", IntakeAssessmentBlock.bucketFor(30));
        assertEquals("clarify", IntakeAssessmentBlock.bucketFor(59));
    }

    @Test
    void bucketFor_60to85_isFull() {
        assertEquals("full", IntakeAssessmentBlock.bucketFor(60));
        assertEquals("full", IntakeAssessmentBlock.bucketFor(72));
        assertEquals("full", IntakeAssessmentBlock.bucketFor(85));
    }

    @Test
    void bucketFor_above85_isFast() {
        assertEquals("fast", IntakeAssessmentBlock.bucketFor(86));
        assertEquals("fast", IntakeAssessmentBlock.bucketFor(95));
        assertEquals("fast", IntakeAssessmentBlock.bucketFor(100));
    }

    // ── run() main paths ──

    @Test
    void validJsonResponse_parsesAndBucketIsCorrect() throws Exception {
        String llmJson = """
                {
                  "clarity_pct": 80,
                  "clarity_breakdown": [
                    {"criterion":"acceptance_criteria_explicit","passed":true,"evidence":"AC listed"},
                    {"criterion":"scope_clear","passed":true,"evidence":"frontend only"},
                    {"criterion":"edge_cases_listed","passed":true,"evidence":"empty input mentioned"},
                    {"criterion":"dod_measurable","passed":true,"evidence":"buttons clickable"},
                    {"criterion":"perf_security_considered","passed":false,"evidence":"no mention"}
                  ],
                  "recommended_path": "fast",
                  "rationale": "Pretty clear UI task."
                }
                """;
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn(llmJson);

        Map<String, Object> input = Map.of("requirement", "Add a cancel-all button to the project page.");
        Map<String, Object> out = block.run(input, blockConfig(), runWith(null));

        assertEquals(80, out.get("clarity_pct"));
        // LLM said "fast", but pct=80 falls in 60..85 → bucket override forces "full"
        assertEquals("full", out.get("recommended_path"),
                "Bucket logic must override LLM's recommended_path");
        List<?> breakdown = (List<?>) out.get("clarity_breakdown");
        assertEquals(5, breakdown.size());
    }

    @Test
    void clarityPercentAbove85_yieldsFastBucket() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {"clarity_pct": 100, "clarity_breakdown": [], "recommended_path": "fast", "rationale":"trivial"}
                """);
        Map<String, Object> out = block.run(Map.of("requirement", "rename foo to bar"),
                blockConfig(), runWith(null));
        assertEquals(100, out.get("clarity_pct"));
        assertEquals("fast", out.get("recommended_path"));
    }

    @Test
    void clarityPercentBelow60_yieldsClarifyBucket() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {"clarity_pct": 20, "clarity_breakdown": [], "recommended_path": "fast", "rationale":"vague"}
                """);
        Map<String, Object> out = block.run(Map.of("requirement", "make it better"),
                blockConfig(), runWith(null));
        assertEquals(20, out.get("clarity_pct"));
        // LLM said "fast", bucket overrides
        assertEquals("clarify", out.get("recommended_path"));
    }

    @Test
    void clarityPercentOutOfRange_isClamped() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {"clarity_pct": 150, "clarity_breakdown": [], "recommended_path": "fast", "rationale":""}
                """);
        Map<String, Object> out = block.run(Map.of("requirement", "anything"),
                blockConfig(), runWith(null));
        assertEquals(100, out.get("clarity_pct"));
    }

    @Test
    void clarityPercentAsString_isParsed() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {"clarity_pct": "72", "clarity_breakdown": [], "recommended_path":"full", "rationale":""}
                """);
        Map<String, Object> out = block.run(Map.of("requirement", "x"),
                blockConfig(), runWith(null));
        assertEquals(72, out.get("clarity_pct"));
        assertEquals("full", out.get("recommended_path"));
    }

    @Test
    void emptyRequirement_returnsClarifyWithoutLlmCall() throws Exception {
        Map<String, Object> out = block.run(new HashMap<>(), blockConfig(), runWith(null));
        assertEquals("clarify", out.get("recommended_path"));
        assertEquals(0, out.get("clarity_pct"));
        verify(mockLlm, never()).complete(any(), any(), any(), anyInt(), anyDouble());
    }

    @Test
    void requirementFromRun_isUsedWhenInputMissing() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {"clarity_pct": 90, "clarity_breakdown": [], "recommended_path":"fast", "rationale":""}
                """);
        Map<String, Object> out = block.run(new HashMap<>(), blockConfig(),
                runWith("Concrete requirement from run state"));
        assertEquals("fast", out.get("recommended_path"));
        verify(mockLlm).complete(any(), any(), contains("Concrete requirement from run state"),
                anyInt(), anyDouble());
    }

    @Test
    void invalidLlmJson_fallsBackToFullPath() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn("not valid json {");

        Map<String, Object> out = block.run(Map.of("requirement", "something"),
                blockConfig(), runWith(null));
        // Conservative default: assume "full" rather than "fast" — full is the safer
        // pipeline path when we don't know how clear the requirement is.
        assertEquals("full", out.get("recommended_path"));
        assertTrue(out.get("rationale").toString().contains("parse error"));
    }

    @Test
    void breakdownItemsAreNormalized() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {
                  "clarity_pct": 60,
                  "clarity_breakdown": [
                    {"criterion":"scope_clear","passed":true,"evidence":"explicit"},
                    {"criterion":"perf_security_considered","passed":"yes","evidence":"discussed"}
                  ],
                  "recommended_path": "full",
                  "rationale": "ok"
                }
                """);
        Map<String, Object> out = block.run(Map.of("requirement", "x"),
                blockConfig(), runWith(null));
        List<?> br = (List<?>) out.get("clarity_breakdown");
        assertEquals(2, br.size());
        Map<?, ?> second = (Map<?, ?>) br.get(1);
        // Non-boolean "passed" defaults to false (we don't try to coerce strings).
        assertEquals(false, second.get("passed"));
    }

    @Test
    void agentModelOverride_isPassedToLlmClient() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {"clarity_pct": 60, "clarity_breakdown": [], "recommended_path":"full", "rationale":""}
                """);
        BlockConfig cfg = blockConfig();
        AgentConfig agent = new AgentConfig();
        agent.setModel("z-ai/glm-4.6");
        cfg.setAgent(agent);

        block.run(Map.of("requirement", "x"), cfg, runWith(null));
        verify(mockLlm).complete(eq("z-ai/glm-4.6"), any(), any(), anyInt(), anyDouble());
    }

    @Test
    void agentTierFallback_whenNoExplicitModel() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {"clarity_pct": 60, "clarity_breakdown": [], "recommended_path":"full", "rationale":""}
                """);
        BlockConfig cfg = blockConfig();
        AgentConfig agent = new AgentConfig();
        agent.setTier("cheap");
        cfg.setAgent(agent);

        block.run(Map.of("requirement", "x"), cfg, runWith(null));
        verify(mockLlm).complete(eq("cheap"), any(), any(), anyInt(), anyDouble());
    }

    @Test
    void noAgentConfig_defaultsToFastTier() throws Exception {
        when(mockLlm.complete(any(), any(), any(), anyInt(), anyDouble())).thenReturn("""
                {"clarity_pct": 60, "clarity_breakdown": [], "recommended_path":"full", "rationale":""}
                """);
        block.run(Map.of("requirement", "x"), blockConfig(), runWith(null));
        verify(mockLlm).complete(eq("fast"), any(), any(), anyInt(), anyDouble());
    }
}
