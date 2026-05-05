package com.workflow.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmClient;
import com.workflow.llm.tooluse.StopReason;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
import com.workflow.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrchestratorBlockTest {

    private OrchestratorBlock block;
    private LlmClient llmClient;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        block = new OrchestratorBlock();
        llmClient = mock(LlmClient.class);
        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.resolve(any())).thenReturn(List.of());

        ReflectionTestUtils.setField(block, "llmClient", llmClient);
        ReflectionTestUtils.setField(block, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(block, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(block, "defaultModel", "anthropic/claude-sonnet-4-6");
    }

    // ── extractJson tests ──────────────────────────────────────────────────────

    @Test
    void extractJson_parsesJsonFenceBlock() throws Exception {
        String text = "Some preamble\n```json\n{\"passed\": true, \"action\": \"continue\"}\n```\ntrailer";
        Map<String, Object> result = invokeExtractJson(text);
        assertEquals(true, result.get("passed"));
        assertEquals("continue", result.get("action"));
        assertFalse(result.containsKey("raw_text"));
    }

    @Test
    void extractJson_fallsBackToBareObject() throws Exception {
        String text = "Here is the result: {\"goal\": \"implement feature\", \"approach\": \"step by step\"}";
        Map<String, Object> result = invokeExtractJson(text);
        assertEquals("implement feature", result.get("goal"));
        assertFalse(result.containsKey("raw_text"));
    }

    @Test
    void extractJson_returnsRawTextOnInvalidJson() throws Exception {
        String text = "This is not JSON at all, just plain text";
        Map<String, Object> result = invokeExtractJson(text);
        assertTrue(result.containsKey("raw_text"));
        assertEquals(text, result.get("raw_text"));
    }

    @Test
    void extractJson_returnsEmptyMapOnNull() throws Exception {
        Map<String, Object> result = invokeExtractJson(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractJson_prefersFenceBlockOverBareObject() throws Exception {
        String text = "{\"decoy\": true}\n```json\n{\"preferred\": true}\n```";
        Map<String, Object> result = invokeExtractJson(text);
        assertEquals(true, result.get("preferred"));
        assertFalse(result.containsKey("decoy"));
    }

    // ── Required fields / raw_text failure ────────────────────────────────────

    @Test
    void run_throwsWhenJsonExtractionFailsCompletely(@TempDir Path wd) throws Exception {
        ToolUseResponse failedResponse = response("totally unparseable response with no json",
            StopReason.END_TURN);
        when(llmClient.completeWithTools(any(), any())).thenReturn(failedResponse);
        when(llmClient.complete(any(), any(), any(), anyInt(), anyDouble()))
            .thenReturn("still not json");

        BlockConfig cfg = reviewConfig(wd, Map.of());
        assertThrows(IllegalStateException.class, () -> block.run(new HashMap<>(), cfg, new PipelineRun()));
    }

    // ── context_blocks in review ───────────────────────────────────────────────

    @Test
    void run_reviewInjectsContextBlocks(@TempDir Path wd) throws Exception {
        String reviewJson = """
            ```json
            {"passed":true,"issues":"","action":"continue","retry_instruction":"","carry_forward":"done"}
            ```""";
        when(llmClient.completeWithTools(any(), any())).thenReturn(response(reviewJson, StopReason.END_TURN));

        BlockConfig cfg = reviewConfig(wd, Map.of("context_blocks", List.of("build_test")));
        Map<String, Object> input = new HashMap<>();
        input.put("build_test", Map.of("exit_code", 0, "output", "BUILD SUCCESSFUL"));

        ArgumentCaptor<ToolUseRequest> captor = ArgumentCaptor.forClass(ToolUseRequest.class);
        when(llmClient.completeWithTools(captor.capture(), any()))
            .thenReturn(response(reviewJson, StopReason.END_TURN));

        block.run(input, cfg, new PipelineRun());

        String userMsg = captor.getValue().userMessage();
        assertTrue(userMsg.contains("build_test"), "user message must contain build_test context");
        assertTrue(userMsg.contains("BUILD SUCCESSFUL"), "user message must contain build output");
    }

    // ── carry_forward and required fields in output ────────────────────────────

    @Test
    void run_reviewOutputHasAllRequiredFields(@TempDir Path wd) throws Exception {
        String reviewJson = """
            ```json
            {"passed":false,"issues":"missing test","action":"retry",
             "retry_instruction":"add unit test","carry_forward":"endpoint implemented"}
            ```""";
        when(llmClient.completeWithTools(any(), any())).thenReturn(response(reviewJson, StopReason.END_TURN));

        Map<String, Object> result = block.run(new HashMap<>(), reviewConfig(wd, Map.of()), new PipelineRun());

        assertEquals(false, result.get("passed"));
        assertEquals("retry", result.get("action"));
        assertEquals("add unit test", result.get("retry_instruction"));
        assertEquals("endpoint implemented", result.get("carry_forward"));
        assertEquals("review", result.get("mode"));
        assertEquals(1, result.get("iterations_used"));
        assertNotNull(result.get("total_cost_usd"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeExtractJson(String text) throws Exception {
        var method = OrchestratorBlock.class.getDeclaredMethod("extractJson", String.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(block, text);
    }

    private static ToolUseResponse response(String finalText, StopReason reason) {
        return new ToolUseResponse(finalText, reason, List.of(), 1, 100, 50, 0.01);
    }

    private BlockConfig reviewConfig(Path wd, Map<String, Object> extra) {
        BlockConfig bc = new BlockConfig();
        bc.setId("review_test");
        bc.setBlock("orchestrator");
        AgentConfig agent = new AgentConfig();
        agent.setModel("anthropic/claude-sonnet-4-6");
        bc.setAgent(agent);
        Map<String, Object> cfg = new HashMap<>(extra);
        cfg.put("mode", "review");
        cfg.put("working_dir", wd.toString());
        bc.setConfig(cfg);
        return bc;
    }

    // ── computeReviewVerdict pure function tests ───────────────────────────────

    @Test
    void verdict_allPassed_continuePassedTrue() {
        var v = OrchestratorBlock.computeReviewVerdict(
            List.of(item("a1", true, "found", ""), item("a2", true, "found", "")),
            List.of(),
            Map.of("a1", "critical", "a2", "important"),
            "retry");
        assertTrue(v.passed());
        assertEquals("continue", v.action());
        assertEquals("", v.issues());
        assertEquals("", v.retryInstruction());
    }

    @Test
    void verdict_criticalFail_retryPassedFalse() {
        var v = OrchestratorBlock.computeReviewVerdict(
            List.of(item("a1", false, "missing impl", "Add the X method"),
                    item("a2", true, "ok", "")),
            List.of(),
            Map.of("a1", "critical", "a2", "important"),
            "retry");
        assertFalse(v.passed());
        assertEquals("retry", v.action());
        assertTrue(v.issues().contains("[critical] a1"));
        assertTrue(v.retryInstruction().contains("a1: Add the X method"));
    }

    @Test
    void verdict_niceToHaveFail_ignored_passedTrue() {
        // Reproduces SM-001 patho-loopback scenario: opus picks at nice-to-have docs items.
        // With priority-aware verdict, nice_to_have failures don't block.
        var v = OrchestratorBlock.computeReviewVerdict(
            List.of(item("a1", true, "ok", ""),
                    item("polish-1", false, "no docstrings", "Add docstrings"),
                    item("polish-2", false, "no comments", "Add comments")),
            List.of(),
            Map.of("a1", "critical", "polish-1", "nice_to_have", "polish-2", "nice_to_have"),
            "retry");
        assertTrue(v.passed(), "nice_to_have fails must NOT block");
        assertEquals("continue", v.action());
        assertEquals("", v.issues());
    }

    @Test
    void verdict_regression_alwaysBlocking() {
        // Even with all checklist items passed, a regression is blocking.
        var v = OrchestratorBlock.computeReviewVerdict(
            List.of(item("a1", true, "ok", "")),
            List.of(Map.of("description", "Build broken", "evidence", "compileJava failed at line 42")),
            Map.of("a1", "critical"),
            "retry");
        assertFalse(v.passed());
        assertEquals("retry", v.action());
        assertTrue(v.issues().contains("[REGRESSION] Build broken"));
        assertTrue(v.retryInstruction().contains("REGRESSION: Build broken"));
    }

    @Test
    void verdict_unknownIdDefaultsToImportant_blocks() {
        // If reviewer cites an id not in priorityById (shouldn't happen but defensive),
        // we treat it as important and let it block.
        var v = OrchestratorBlock.computeReviewVerdict(
            List.of(item("ghost-id", false, "?", "fix?")),
            List.of(),
            Map.of(),
            "retry");
        assertFalse(v.passed());
        assertEquals("retry", v.action());
    }

    @Test
    void verdict_escalateRespectedWhenReviewerSets() {
        // Reviewer explicitly escalated (architectural problem). Verdict preserves it.
        var v = OrchestratorBlock.computeReviewVerdict(
            List.of(item("a1", false, "wrong abstraction", "")),
            List.of(),
            Map.of("a1", "critical"),
            "escalate");
        assertFalse(v.passed());
        assertEquals("escalate", v.action());
    }

    @Test
    void verdict_escalateIgnoredWhenAllPassed() {
        // Even if reviewer says escalate, if everything passes we continue.
        var v = OrchestratorBlock.computeReviewVerdict(
            List.of(item("a1", true, "ok", "")),
            List.of(),
            Map.of("a1", "critical"),
            "escalate");
        assertTrue(v.passed());
        assertEquals("continue", v.action());
    }

    @Test
    void verdict_emptyChecklistAndNoRegressions_passed() {
        var v = OrchestratorBlock.computeReviewVerdict(
            List.of(), List.of(), Map.of(), "retry");
        assertTrue(v.passed());
        assertEquals("continue", v.action());
    }

    private static Map<String, Object> item(String id, boolean passed, String evidence, String fix) {
        return Map.of("id", id, "passed", passed, "evidence", evidence, "fix", fix);
    }
}
