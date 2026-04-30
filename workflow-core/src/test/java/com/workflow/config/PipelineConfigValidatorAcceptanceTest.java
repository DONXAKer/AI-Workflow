package com.workflow.config;

import com.workflow.blocks.Block;
import com.workflow.core.BlockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance test: every YAML pipeline shipped with the repo must pass
 * {@link PipelineConfigValidator}. Catches regressions where a YAML edit introduces
 * a forward-ref or unknown block type without anyone noticing until a runtime failure.
 *
 * <p>The validator is wired with a manually-built {@link BlockRegistry} that mirrors the
 * full set of registered block types (no Spring context needed). When a new block type
 * is added to the codebase, add it here too.
 */
class PipelineConfigValidatorAcceptanceTest {

    /**
     * Mirror of the names returned by every {@code @Component} {@code Block} in
     * {@code com.workflow.blocks}. Keep in sync.
     */
    private static final List<String> ALL_BLOCK_TYPES = List.of(
        "analysis",
        "ai_review",
        "agent_verify",
        "agent_with_tools",
        "build",
        "business_intake",
        "clarification",
        "claude_code_shell",
        "code_generation",
        "deploy",
        "git_branch_input",
        "github_actions",
        "github_pr",
        "gitlab_ci",
        "gitlab_mr",
        "http_get",
        "mr_input",
        "orchestrator",
        "release_notes",
        "rollback",
        "run_tests",
        "shell_exec",
        "task_input",
        "task_md_input",
        "test_generation",
        "vcs_merge",
        "verify",
        "verify_prod",
        "youtrack_input",
        "youtrack_tasks",
        "youtrack_tasks_input"
    );

    private PipelineConfigValidator validator;
    private PipelineConfigLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        BlockRegistry registry = new BlockRegistry();
        List<Block> stubs = new ArrayList<>();
        for (String name : ALL_BLOCK_TYPES) stubs.add(stubBlock(name));
        ReflectionTestUtils.setField(registry, "allBlocks", stubs);
        Method init = BlockRegistry.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(registry);
        this.validator = new PipelineConfigValidator(registry);
        this.loader = new PipelineConfigLoader();
    }

    private static Block stubBlock(String name) {
        return new Block() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> run(Map<String, Object> input, BlockConfig config,
                                                     com.workflow.core.PipelineRun run) {
                return Map.of();
            }
        };
    }

    /** Try the working-dir-relative path first (when running gradle in workflow-core), then prefix with the module. */
    private Path resolveYaml(String relative) {
        Path p = Paths.get("src/main/resources/config/" + relative);
        if (!Files.exists(p)) {
            p = Paths.get("workflow-core/src/main/resources/config/" + relative);
        }
        assertTrue(Files.exists(p), "yaml not found at " + p.toAbsolutePath());
        return p;
    }

    @Test
    void featureYaml_isValid() throws Exception {
        PipelineConfig cfg = loader.loadRaw(resolveYaml("feature.yaml"));
        ValidationResult r = validator.validate(cfg);
        assertTrue(r.valid(), () -> "feature.yaml validation errors: " + r.errors());
    }

    @Test
    void pipelineFullFlowYaml_isValid() throws Exception {
        PipelineConfig cfg = loader.loadRaw(resolveYaml("pipeline.full-flow.yaml"));
        ValidationResult r = validator.validate(cfg);
        assertTrue(r.valid(), () -> "pipeline.full-flow.yaml validation errors: " + r.errors());
    }
}
