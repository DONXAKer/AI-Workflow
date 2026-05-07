package com.workflow.blocks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PR-1 changes to {@link FieldSchema} and {@link BlockMetadata}:
 * level-default-from-required heuristic, backwards-compat constructors, and
 * non-null defaults for the new {@code outputs} / {@code recommendedRank}
 * components.
 *
 * <p>Also instantiates each block we annotated in PR-1 (without Spring context)
 * and asserts that {@code getMetadata().outputs()} is non-empty — guards against
 * a future refactor accidentally dropping the outputs back to {@link List#of()}.
 */
class BlockMetadataOutputsTest {

    // ── FieldSchema.level defaulting ───────────────────────────────────────────

    @Test
    void fieldSchema_requiredField_defaultsToEssential() {
        FieldSchema f = FieldSchema.requiredString("user_message", "User msg", "desc");
        assertTrue(f.required());
        assertEquals("essential", f.level(),
            "required fields must default to level=essential");
    }

    @Test
    void fieldSchema_optionalField_defaultsToAdvanced() {
        FieldSchema f = FieldSchema.string("preload_from", "Preload", "desc");
        assertFalse(f.required());
        assertEquals("advanced", f.level(),
            "optional fields must default to level=advanced");
    }

    @Test
    void fieldSchema_explicitLevel_overridesDefault() {
        FieldSchema f = FieldSchema.string("working_dir", "WD", "desc").withLevel("essential");
        assertFalse(f.required());
        assertEquals("essential", f.level(), "withLevel must override the heuristic");
    }

    @Test
    void fieldSchema_legacy7ArgConstructor_appliesDefault() {
        // 7-arg ctor is the backwards-compat surface: callers that don't pass level
        // must get the heuristic default applied via the canonical compact ctor.
        FieldSchema required = new FieldSchema("x", "X", "string", true, null, "desc", Map.of());
        FieldSchema optional = new FieldSchema("y", "Y", "string", false, null, "desc", Map.of());
        assertEquals("essential", required.level());
        assertEquals("advanced", optional.level());
    }

    @Test
    void fieldSchema_outputFactory_setsEssential() {
        // FieldSchema.output() is the helper used in BlockMetadata.outputs() lists —
        // outputs default to essential because operators reference them downstream.
        FieldSchema f = FieldSchema.output("summary", "Summary", "string", "desc");
        assertEquals("essential", f.level());
        assertFalse(f.required(), "outputs are runtime-produced — required is meaningless, should be false");
    }

    // ── BlockMetadata constructor compat ───────────────────────────────────────

    @Test
    void blockMetadata_defaultFor_hasNonNullOutputsAndZeroRank() {
        BlockMetadata md = BlockMetadata.defaultFor("some_unknown_block");
        assertNotNull(md.outputs(), "outputs must never be null");
        assertTrue(md.outputs().isEmpty(), "default outputs are empty");
        assertEquals(0, md.recommendedRank(), "default rank is 0");
    }

    @Test
    void blockMetadata_5argLegacyCtor_defaultsOutputsAndRank() {
        BlockMetadata md = new BlockMetadata("L", "general",
            List.of(FieldSchema.string("k", "K", "d")),
            false, Map.of());
        assertEquals(Phase.ANY, md.phase(), "5-arg ctor must default phase to ANY");
        assertNotNull(md.outputs());
        assertTrue(md.outputs().isEmpty());
        assertEquals(0, md.recommendedRank());
    }

    @Test
    void blockMetadata_6argLegacyCtor_defaultsOutputsAndRank() {
        BlockMetadata md = new BlockMetadata("L", "agent", Phase.IMPLEMENT,
            List.of(), true, Map.of());
        assertEquals(Phase.IMPLEMENT, md.phase());
        assertNotNull(md.outputs());
        assertTrue(md.outputs().isEmpty());
        assertEquals(0, md.recommendedRank());
    }

    @Test
    void blockMetadata_canonical8argCtor_preservesOutputsAndRank() {
        FieldSchema out = FieldSchema.output("result", "Result", "string", "desc");
        BlockMetadata md = new BlockMetadata("L", "agent", Phase.IMPLEMENT,
            List.of(), false, Map.of(),
            List.of(out), 75);
        assertEquals(1, md.outputs().size());
        assertEquals("result", md.outputs().get(0).name());
        assertEquals(75, md.recommendedRank());
    }

    @Test
    void blockMetadata_nullOutputs_compactCtorReplacesWithEmptyList() {
        BlockMetadata md = new BlockMetadata("L", "agent", Phase.IMPLEMENT,
            List.of(), false, Map.of(),
            null, 0);
        assertNotNull(md.outputs());
        assertTrue(md.outputs().isEmpty());
    }

    // ── Top-10 blocks expose non-empty outputs ─────────────────────────────────
    //
    // We instantiate the block beans directly (no Spring context) — for blocks
    // with @Autowired collaborators this is fine because getMetadata() is a pure
    // method that doesn't touch them.

    @Test
    void analysisBlock_declaresOutputs() {
        BlockMetadata md = new AnalysisBlock().getMetadata();
        assertFalse(md.outputs().isEmpty(), "AnalysisBlock must declare outputs");
        assertEquals(100, md.recommendedRank());
        assertNamesContain(md.outputs(), "summary", "acceptance_checklist", "needs_clarification");
    }

    @Test
    void codeGenerationBlock_declaresOutputs() {
        BlockMetadata md = new CodeGenerationBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(100, md.recommendedRank());
        assertNamesContain(md.outputs(), "branch_name", "changes", "commit_message");
    }

    @Test
    void verifyBlock_declaresOutputs() {
        BlockMetadata md = new VerifyBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(80, md.recommendedRank());
        assertNamesContain(md.outputs(), "passed", "issues", "subject_block");
    }

    @Test
    void agentWithToolsBlock_declaresOutputsAndLevels() {
        BlockMetadata md = new AgentWithToolsBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(100, md.recommendedRank());
        assertNamesContain(md.outputs(), "final_text", "tool_calls_made", "total_cost_usd");
        // Per plan: user_message / working_dir / allowed_tools → essential.
        assertLevelEquals(md.configFields(), "user_message", "essential");
        assertLevelEquals(md.configFields(), "working_dir", "essential");
        assertLevelEquals(md.configFields(), "allowed_tools", "essential");
        // ...bash_allowlist / max_iterations / budget_usd_cap / preload_from → advanced.
        assertLevelEquals(md.configFields(), "max_iterations", "advanced");
        assertLevelEquals(md.configFields(), "budget_usd_cap", "advanced");
        assertLevelEquals(md.configFields(), "preload_from", "advanced");
    }

    @Test
    void orchestratorBlock_declaresUnionOutputs() {
        BlockMetadata md = new OrchestratorBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(70, md.recommendedRank());
        // Plan-mode outputs.
        assertNamesContain(md.outputs(), "goal", "files_to_touch", "definition_of_done");
        // Review-mode outputs.
        assertNamesContain(md.outputs(), "passed", "checklist_status", "regressions");
        // Common.
        assertNamesContain(md.outputs(), "mode", "iterations_used");
        // Per plan: mode / context_blocks / plan_block → essential.
        assertLevelEquals(md.configFields(), "mode", "essential");
        assertLevelEquals(md.configFields(), "context_blocks", "essential");
        assertLevelEquals(md.configFields(), "plan_block", "essential");
    }

    @Test
    void githubPrBlock_declaresOutputs() {
        BlockMetadata md = new GitHubPRBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(100, md.recommendedRank());
        assertEquals(Phase.PUBLISH, md.phase());
        assertEquals("output", md.category());
        assertNamesContain(md.outputs(), "pr_number", "pr_url", "branch", "youtrack_issues_linked");
    }

    @Test
    void gitlabMrBlock_declaresOutputs() {
        BlockMetadata md = new GitLabMRBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(100, md.recommendedRank());
        assertEquals(Phase.PUBLISH, md.phase());
        assertEquals("output", md.category());
        assertNamesContain(md.outputs(), "mr_id", "mr_url", "youtrack_issues_linked");
    }

    @Test
    void runTestsBlock_declaresOutputsAndConfig() {
        BlockMetadata md = new RunTestsBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(80, md.recommendedRank());
        assertEquals(Phase.VERIFY, md.phase());
        assertEquals("verify", md.category());
        assertNamesContain(md.outputs(), "tests_run", "tests_passed", "failed_tests", "status");
        // configFields per plan: type / environment / suite / timeout_seconds.
        assertNamesContain(md.configFields(), "type", "environment", "suite", "timeout_seconds");
    }

    @Test
    void agentVerifyBlock_declaresOutputsAndLevels() {
        BlockMetadata md = new AgentVerifyBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(90, md.recommendedRank());
        assertNamesContain(md.outputs(), "verification_results", "failed_items", "passed_items",
            "regression_flags");
        // Per plan: subject / working_dir / pass_threshold → essential.
        assertLevelEquals(md.configFields(), "subject", "essential");
        assertLevelEquals(md.configFields(), "working_dir", "essential");
        assertLevelEquals(md.configFields(), "pass_threshold", "essential");
    }

    @Test
    void taskMdInputBlock_declaresOutputsAndLevels() {
        BlockMetadata md = new TaskMdInputBlock().getMetadata();
        assertFalse(md.outputs().isEmpty());
        assertEquals(100, md.recommendedRank());
        assertNamesContain(md.outputs(), "feat_id", "slug", "title", "body",
            "as_is", "to_be", "out_of_scope", "acceptance",
            "needs_bp", "needs_server", "needs_client", "needs_contract_change", "is_greenfield");
        // file_path is required → essential.
        assertLevelEquals(md.configFields(), "file_path", "essential");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static void assertNamesContain(List<FieldSchema> fields, String... required) {
        List<String> names = fields.stream().map(FieldSchema::name).toList();
        for (String n : required) {
            assertTrue(names.contains(n),
                () -> "expected field/output '" + n + "' in " + names);
        }
    }

    private static void assertLevelEquals(List<FieldSchema> fields, String name, String expectedLevel) {
        FieldSchema f = fields.stream().filter(x -> name.equals(x.name())).findFirst()
            .orElseThrow(() -> new AssertionError("no field '" + name + "' in configFields"));
        assertEquals(expectedLevel, f.level(),
            () -> "field '" + name + "' expected level=" + expectedLevel + ", got=" + f.level());
    }
}
