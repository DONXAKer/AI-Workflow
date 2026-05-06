package com.workflow.blocks;

import java.util.Map;

/**
 * Pipeline phase a block belongs to. Phases form a strict linear order
 * (INTAKE → ANALYZE → IMPLEMENT → VERIFY → PUBLISH → RELEASE) and the
 * validator enforces that {@code depends_on} edges respect this order.
 *
 * <p>{@link #ANY} marks polymorphic blocks (shell_exec, http_get, orchestrator)
 * that can legitimately appear in any phase. They are transparent to the
 * monotonicity check — neither side of an edge involving an ANY block is
 * compared. A polymorphic instance without an explicit YAML override emits a
 * WARN so the operator can pin its role.
 */
public enum Phase {
    INTAKE,
    ANALYZE,
    IMPLEMENT,
    VERIFY,
    PUBLISH,
    RELEASE,
    ANY;

    /**
     * Returns this phase's position in the linear order, or {@code -1} for
     * {@link #ANY}. Comparison must check for ANY before calling.
     */
    public int order() {
        return this == ANY ? -1 : ordinal();
    }

    /** True if both phases are concrete (not ANY) and {@code b}'s order is strictly less than {@code a}'s. */
    public static boolean violatesMonotonic(Phase predecessor, Phase successor) {
        if (predecessor == ANY || successor == ANY) return false;
        return successor.order() < predecessor.order();
    }

    /**
     * Canonical mapping of block-type name → default phase. Source of truth for
     * the per-block default — {@link BlockMetadata#defaultFor(String)} reads
     * from here so blocks that do not override {@code getMetadata()} still
     * receive the correct phase. Blocks with a hand-written {@code getMetadata()}
     * should pass an explicit {@link Phase} to the canonical 6-arg
     * {@link BlockMetadata} constructor — this map is the fallback only.
     */
    private static final Map<String, Phase> BLOCK_TYPE_PHASES = Map.ofEntries(
        // INTAKE
        Map.entry("business_intake", INTAKE),
        Map.entry("git_branch_input", INTAKE),
        Map.entry("mr_input", INTAKE),
        Map.entry("task_input", INTAKE),
        Map.entry("task_md_input", INTAKE),
        Map.entry("youtrack_input", INTAKE),
        Map.entry("youtrack_tasks_input", INTAKE),
        // ANALYZE
        Map.entry("analysis", ANALYZE),
        Map.entry("clarification", ANALYZE),
        Map.entry("youtrack_tasks", ANALYZE),
        // IMPLEMENT
        Map.entry("agent_with_tools", IMPLEMENT),
        Map.entry("claude_code_shell", IMPLEMENT),
        Map.entry("code_generation", IMPLEMENT),
        Map.entry("test_generation", IMPLEMENT),
        // VERIFY
        Map.entry("agent_verify", VERIFY),
        Map.entry("ai_review", VERIFY),
        Map.entry("build", VERIFY),
        Map.entry("run_tests", VERIFY),
        Map.entry("verify", VERIFY),
        // PUBLISH
        Map.entry("github_actions", PUBLISH),
        Map.entry("github_pr", PUBLISH),
        Map.entry("gitlab_ci", PUBLISH),
        Map.entry("gitlab_mr", PUBLISH),
        Map.entry("vcs_merge", PUBLISH),
        // RELEASE
        Map.entry("deploy", RELEASE),
        Map.entry("release_notes", RELEASE),
        Map.entry("rollback", RELEASE),
        Map.entry("verify_prod", RELEASE),
        // ANY (polymorphic)
        Map.entry("http_get", ANY),
        Map.entry("orchestrator", ANY),
        Map.entry("shell_exec", ANY)
    );

    public static Phase forBlockType(String name) {
        if (name == null) return ANY;
        return BLOCK_TYPE_PHASES.getOrDefault(name, ANY);
    }
}
