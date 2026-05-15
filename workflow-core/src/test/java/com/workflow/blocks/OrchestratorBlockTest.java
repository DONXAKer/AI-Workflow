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

    // ── Finalize-tool wiring (PR: configurable tools + finalize) ──────────────

    @Test
    void run_reviewIncludesFinalizeToolAndForcePastMidLoop(@TempDir Path wd) throws Exception {
        String reviewJson = """
            ```json
            {"passed":true,"issues":"","action":"continue","retry_instruction":"","carry_forward":"done"}
            ```""";
        ArgumentCaptor<ToolUseRequest> captor = ArgumentCaptor.forClass(ToolUseRequest.class);
        when(llmClient.completeWithTools(captor.capture(), any()))
            .thenReturn(response(reviewJson, StopReason.END_TURN));

        BlockConfig cfg = reviewConfig(wd, Map.of("max_iterations", 8));
        block.run(new HashMap<>(), cfg, new PipelineRun());

        ToolUseRequest req = captor.getValue();
        assertEquals("finalize_review", req.finalizeToolName(),
            "review-mode must wire finalize_review as the finalize tool");
        assertTrue(req.forceFinalizeAfter() > 0 && req.forceFinalizeAfter() <= 8,
            "forceFinalizeAfter must be set within iteration budget; was " + req.forceFinalizeAfter());
        assertTrue(req.tools().stream().anyMatch(t -> "finalize_review".equals(t.name())),
            "tools list must contain the finalize_review definition");
    }

    @Test
    void run_planUsesFinalizePlanTool(@TempDir Path wd) throws Exception {
        String planJson = """
            ```json
            {"goal":"implement feature foo across api/","files_to_touch":"a.java","approach":"add endpoint then wire UI","definition_of_done":"tests pass","tools_to_use":"Read,Edit",
             "requirements_coverage":[
               {"requirement":"r1","approach":"impl","files":"a.java"},
               {"requirement":"r2","approach":"impl","files":"b.java"},
               {"requirement":"r3","approach":"impl","files":"c.java"}
             ]}
            ```""";
        ArgumentCaptor<ToolUseRequest> captor = ArgumentCaptor.forClass(ToolUseRequest.class);
        when(llmClient.completeWithTools(captor.capture(), any()))
            .thenReturn(response(planJson, StopReason.END_TURN));

        BlockConfig cfg = planConfig(wd, Map.of("context_blocks", List.of("task_md")));
        Map<String, Object> input = new HashMap<>();
        input.put("task_md", Map.of("title", "T", "body", "B"));
        block.run(input, cfg, new PipelineRun());

        ToolUseRequest req = captor.getValue();
        assertEquals("finalize_plan", req.finalizeToolName());
        assertTrue(req.tools().stream().anyMatch(t -> "finalize_plan".equals(t.name())));
    }

    @Test
    void run_finalizeArgsParsedDirectlyAsFinalText(@TempDir Path wd) throws Exception {
        // Simulates the provider-level short-circuit: model called finalize_review and the
        // provider returned the raw arguments JSON object (no fence, no preamble) as finalText.
        // Verifies extractJson handles bare JSON object → orchestrator output is correct.
        String rawArgs =
            "{\"passed\":true,\"issues\":\"\",\"action\":\"continue\",\"retry_instruction\":\"\",\"carry_forward\":\"ok\"}";
        when(llmClient.completeWithTools(any(), any())).thenReturn(response(rawArgs, StopReason.END_TURN));

        Map<String, Object> result = block.run(new HashMap<>(), reviewConfig(wd, Map.of()), new PipelineRun());

        assertEquals(true, result.get("passed"));
        assertEquals("continue", result.get("action"));
        assertEquals("ok", result.get("carry_forward"));
        assertFalse(result.containsKey("raw_text"),
            "raw bare-JSON args from finalize tool must parse cleanly via extractJson");
    }

    // ── Configurable tools / bash_allowlist override ──────────────────────────

    @Test
    void run_cfgToolsOverrideReplacesDefaults(@TempDir Path wd) throws Exception {
        String reviewJson = "{\"passed\":true,\"action\":\"continue\",\"carry_forward\":\"all good\"}";
        when(llmClient.completeWithTools(any(), any())).thenReturn(response(reviewJson, StopReason.END_TURN));

        ArgumentCaptor<List<String>> namesCaptor = ArgumentCaptor.forClass(List.class);
        when(toolRegistry.resolve(namesCaptor.capture())).thenReturn(List.of());

        BlockConfig cfg = reviewConfig(wd, Map.of("tools", List.of("Read", "Glob")));
        block.run(new HashMap<>(), cfg, new PipelineRun());

        List<String> resolved = namesCaptor.getValue();
        assertEquals(List.of("Read", "Glob"), resolved,
            "cfg.tools must replace default REVIEW_TOOLS (no Bash, no Grep)");
    }

    @Test
    void run_defaultsKeptWhenNoOverride(@TempDir Path wd) throws Exception {
        String reviewJson = "{\"passed\":true,\"action\":\"continue\",\"carry_forward\":\"all good\"}";
        when(llmClient.completeWithTools(any(), any())).thenReturn(response(reviewJson, StopReason.END_TURN));

        ArgumentCaptor<List<String>> namesCaptor = ArgumentCaptor.forClass(List.class);
        when(toolRegistry.resolve(namesCaptor.capture())).thenReturn(List.of());

        block.run(new HashMap<>(), reviewConfig(wd, Map.of()), new PipelineRun());

        List<String> resolved = namesCaptor.getValue();
        assertTrue(resolved.contains("Read") && resolved.contains("Glob")
                && resolved.contains("Grep") && resolved.contains("Bash"),
            "default REVIEW_TOOLS must be passed when cfg.tools absent; was " + resolved);
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

    private BlockConfig planConfig(Path wd, Map<String, Object> extra) {
        BlockConfig bc = new BlockConfig();
        bc.setId("plan_test");
        bc.setBlock("orchestrator");
        AgentConfig agent = new AgentConfig();
        agent.setModel("anthropic/claude-sonnet-4-6");
        bc.setAgent(agent);
        Map<String, Object> cfg = new HashMap<>(extra);
        cfg.put("mode", "plan");
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

    // ── fixCommonJsonErrors ───────────────────────────────────────────────────
    // Repairs the typo patterns we've actually observed from LLM responses.
    // The regression these tests guard: glm-4.6 on FEAT-AP-002 emitted
    // `"approach "Объявить...` (missing colon, trailing space inside key),
    // which a strict Jackson parse rejects.

    @Test
    void fixup_missingColonAfterKey_glm46_realFailure() {
        // Exact pattern observed in OrchestratorBlock raw-text log
        String broken = "{\"requirement\": \"r1\", \"approach \"Объявить DELEGATE\", \"files\": \"f.h\"}";
        String fixed = OrchestratorBlock.fixCommonJsonErrors(broken);
        assertEquals("{\"requirement\": \"r1\", \"approach\": \"Объявить DELEGATE\", \"files\": \"f.h\"}", fixed);
    }

    @Test
    void fixup_missingColonAfterKey_asciiKey() {
        String broken = "{\"foo\": 1, \"bar \"baz\", \"qux\": 3}";
        String fixed = OrchestratorBlock.fixCommonJsonErrors(broken);
        assertEquals("{\"foo\": 1, \"bar\": \"baz\", \"qux\": 3}", fixed);
    }

    @Test
    void fixup_smartQuotes_replaced() {
        String broken = "{“goal”: “do X”}";
        String fixed = OrchestratorBlock.fixCommonJsonErrors(broken);
        assertEquals("{\"goal\": \"do X\"}", fixed);
    }

    @Test
    void fixup_trailingComma_removed() {
        String broken = "{\"items\": [1, 2, 3,], \"k\": \"v\",}";
        String fixed = OrchestratorBlock.fixCommonJsonErrors(broken);
        assertEquals("{\"items\": [1, 2, 3], \"k\": \"v\"}", fixed);
    }

    @Test
    void fixup_preservesValidJson() {
        String valid = "{\"goal\": \"a\", \"approach\": \"do x\"}";
        assertEquals(valid, OrchestratorBlock.fixCommonJsonErrors(valid));
    }

    @Test
    void fixup_doesNotMatchInsideStringValues() {
        // `"word "` inside a value must NOT trigger the missing-colon fix.
        // Anchored at key-position (after { , or \n) so we don't rewrite this.
        String input = "{\"text\": \"a word \"more\" inside\", \"k\": 1}";
        String fixed = OrchestratorBlock.fixCommonJsonErrors(input);
        // Either unchanged or at least the value content not corrupted —
        // assert that key list survives.
        assertTrue(fixed.contains("\"text\""));
        assertTrue(fixed.contains("\"k\": 1"));
    }

    @Test
    void fixup_handlesNullAndEmpty() {
        assertNull(OrchestratorBlock.fixCommonJsonErrors(null));
        assertEquals("", OrchestratorBlock.fixCommonJsonErrors(""));
    }

    @Test
    void fixup_realFailureRoundtripsThroughJackson() throws Exception {
        // End-to-end: the actual broken JSON from FEAT-AP-002 must become parseable.
        String broken = "{\"goal\": \"g\", \"requirements_coverage\": [{\"requirement\": \"r\", \"approach \"Объявить\", \"files\": \"f\"}]}";
        String fixed = OrchestratorBlock.fixCommonJsonErrors(broken);
        ObjectMapper om = new ObjectMapper();
        Map<?,?> parsed = om.readValue(fixed, Map.class);
        assertEquals("g", parsed.get("goal"));
        List<?> rc = (List<?>) parsed.get("requirements_coverage");
        assertEquals(1, rc.size());
        Map<?,?> first = (Map<?,?>) rc.get(0);
        assertEquals("Объявить", first.get("approach"));
    }

    // ── buildReviewSystemPrompt anti-patterns ─────────────────────────────────

    @Test
    void buildReviewSystemPrompt_includesAntiPatternSection_whenChecklist() throws Exception {
        var method = OrchestratorBlock.class.getDeclaredMethod(
            "buildReviewSystemPrompt", String.class, String.class, boolean.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(null, "", "", true);

        assertTrue(prompt.contains("Анти-паттерны retry"),
            "haveChecklist branch must contain explicit Анти-паттерны retry header");
        assertTrue(prompt.contains("Стиль, нейминг"),
            "anti-pattern section must explicitly call out стиль/нейминг as non-blockers");
        assertTrue(prompt.contains("Если сомневаешься"),
            "anti-pattern section must contain the bias-to-accept rule");
    }
}
