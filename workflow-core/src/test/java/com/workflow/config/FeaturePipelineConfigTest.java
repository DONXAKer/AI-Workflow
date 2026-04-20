package com.workflow.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural checks on {@code resources/config/feature.yaml} — the reference pipeline
 * shipped with Phase 1. We verify that:
 *
 * <ul>
 *   <li>The YAML loads cleanly into {@link PipelineConfig}.</li>
 *   <li>Every referenced block type belongs to the set we implemented in M2/M3.</li>
 *   <li>All {@code depends_on} ids point at blocks defined earlier in the file.</li>
 *   <li>Verify-loopback targets exist.</li>
 *   <li>The {@code implement} entry point's {@code from_block} matches a real block id.</li>
 * </ul>
 *
 * <p>A live pipeline run is acceptance-tested by the user against a real project —
 * this test catches structural regressions without needing OpenRouter credentials.
 */
class FeaturePipelineConfigTest {

    private static final Set<String> KNOWN_BLOCK_TYPES = Set.of(
        "task_md_input",
        "agent_with_tools",
        "shell_exec",
        "claude_code_shell",
        "verify",
        "analysis",
        "code_generation",
        "clarification",
        "youtrack_input",
        "youtrack_tasks_input",
        "youtrack_tasks",
        "git_branch_input",
        "gitlab_mr",
        "gitlab_ci",
        "github_pr",
        "github_actions",
        "mr_input",
        "build",
        "run_tests",
        "test_generation",
        "deploy",
        "rollback",
        "release_notes",
        "ai_review",
        "verify_prod",
        "vcs_merge",
        "business_intake",
        "task_input"
    );

    private Path resolveFeatureYaml() {
        Path p = Paths.get("src/main/resources/config/feature.yaml");
        if (!Files.exists(p)) {
            p = Paths.get("workflow-core/src/main/resources/config/feature.yaml");
        }
        assertTrue(Files.exists(p), "feature.yaml not found at " + p.toAbsolutePath());
        return p;
    }

    @Test
    void featurePipelineLoadsAndIsWellFormed() throws Exception {
        PipelineConfigLoader loader = new PipelineConfigLoader();
        PipelineConfig config = loader.load(resolveFeatureYaml());

        assertNotNull(config);
        assertEquals("feature", config.getName());

        List<BlockConfig> blocks = config.getPipeline();
        assertFalse(blocks.isEmpty(), "pipeline must declare blocks");

        Set<String> seen = new HashSet<>();
        for (BlockConfig b : blocks) {
            assertNotNull(b.getId(), "block missing id");
            assertNotNull(b.getBlock(), "block '" + b.getId() + "' missing type");
            assertTrue(KNOWN_BLOCK_TYPES.contains(b.getBlock()),
                "block '" + b.getId() + "' uses unknown type '" + b.getBlock()
                    + "' — register it in KNOWN_BLOCK_TYPES or fix the YAML");

            if (b.getDependsOn() != null) {
                for (String dep : b.getDependsOn()) {
                    assertTrue(seen.contains(dep),
                        "block '" + b.getId() + "' depends on '" + dep
                            + "' which is not defined earlier in the pipeline");
                }
            }
            seen.add(b.getId());
        }

        // Verify loopback targets must resolve to an earlier block.
        for (BlockConfig b : blocks) {
            if (b.getVerify() == null) continue;
            if (b.getVerify().getOnFail() == null) continue;
            if (!"loopback".equals(b.getVerify().getOnFail().getAction())) continue;
            String target = b.getVerify().getOnFail().getTarget();
            assertTrue(seen.contains(target),
                "verify '" + b.getId() + "' loops back to unknown target '" + target + "'");
        }

        // Entry point referenced by name must exist.
        assertNotNull(config.getEntryPoints());
        assertFalse(config.getEntryPoints().isEmpty(), "feature.yaml should declare entry points");
        EntryPointConfig implement = config.getEntryPoints().stream()
            .filter(ep -> "implement".equals(ep.getId())).findFirst()
            .orElseThrow(() -> new AssertionError("no 'implement' entry point"));
        assertTrue(seen.contains(implement.getFromBlock()),
            "entry point 'implement' points at unknown from_block '"
                + implement.getFromBlock() + "'");
    }
}
