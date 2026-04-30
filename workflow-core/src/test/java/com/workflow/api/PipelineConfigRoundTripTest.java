package com.workflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.workflow.blocks.Block;
import com.workflow.config.BlockConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigLoader;
import com.workflow.config.PipelineConfigValidator;
import com.workflow.config.PipelineConfigWriter;
import com.workflow.core.BlockRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end round-trip test for the Pipeline Editor save path.
 *
 * <p>Loads {@code resources/config/feature.yaml}, writes it back via
 * {@link PipelineConfigWriter#writeFull(Path, PipelineConfig)}, re-loads the temp file,
 * and asserts the two configs are structurally identical when normalised to JSON.
 *
 * <p>This catches Jackson field-loss regressions: if a {@link PipelineConfig} or
 * {@link BlockConfig} field forgets {@code @JsonProperty} or has the wrong getter shape,
 * the editor would silently corrupt YAMLs on save. Adding a new field to those POJOs
 * without updating this test will fail loudly.
 */
class PipelineConfigRoundTripTest {

    private static PipelineConfigLoader loader;
    private static PipelineConfigWriter writer;
    private static ObjectMapper jsonMapper;

    @BeforeAll
    static void setup() throws Exception {
        loader = new PipelineConfigLoader();

        // Build a registry with stub blocks for every type referenced by feature.yaml
        // so the validator doesn't reject them as unknown.
        BlockRegistry registry = new BlockRegistry();
        List<Block> stubs = new ArrayList<>();
        for (String name : List.of("task_md_input", "shell_exec", "agent_with_tools",
                "orchestrator", "verify")) {
            stubs.add(stubBlock(name));
        }
        ReflectionTestUtils.setField(registry, "allBlocks", stubs);
        Method init = BlockRegistry.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(registry);

        PipelineConfigValidator validator = new PipelineConfigValidator(registry);
        writer = new PipelineConfigWriter(loader, validator);
        jsonMapper = new ObjectMapper();
        jsonMapper.registerModule(new JavaTimeModule());
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

    /**
     * Loading {@code feature.yaml}, writing it back via {@code writeFull}, and
     * re-loading produces a structurally identical config.
     *
     * <p>Comparison normalises both sides to JSON via the same Jackson mapper to
     * sidestep Java {@code equals()} being undefined on the config POJOs.
     */
    @Test
    void featureYamlRoundTripsWithoutDataLoss(@TempDir Path tempDir) throws Exception {
        // Load the bundled template
        Path templatePath = copyTemplateToTemp(tempDir, "feature-original.yaml");
        PipelineConfig original = loader.loadRaw(templatePath);

        // Sanity-check the template parsed something we recognise
        assertNotNull(original.getName(), "template must have a name");
        assertTrue(original.getPipeline().size() >= 4,
            "template must have a non-trivial pipeline list");

        // Verify the agent's maxTokens survives the read (regression: AgentConfig
        // had @JsonProperty("max_tokens") that silently dropped camelCase keys
        // present in the bundled feature.yaml).
        boolean anyAgentWithTokens = original.getPipeline().stream()
            .anyMatch(b -> b.getAgent() != null && b.getAgent().getMaxTokens() != null);
        if (original.getPipeline().stream().anyMatch(b -> b.getAgent() != null)) {
            assertTrue(anyAgentWithTokens,
                "Expected at least one block's agent.maxTokens to be parsed (regression guard).");
        }

        // Write it back to a separate temp path through the editor save path
        Path savedPath = tempDir.resolve("feature-roundtrip.yaml");
        // writeFull validates pre + post — if anything is amiss it throws here
        PipelineConfig saved = writer.writeFull(savedPath, original);

        // Re-load from disk and compare to the original
        PipelineConfig reloaded = loader.loadRaw(savedPath);

        String originalJson = jsonMapper.writeValueAsString(original);
        String savedJson = jsonMapper.writeValueAsString(saved);
        String reloadedJson = jsonMapper.writeValueAsString(reloaded);

        assertEquals(originalJson, reloadedJson,
            "Round-trip lost data — see diff between original.yaml and roundtrip.yaml");
        assertEquals(originalJson, savedJson,
            "writeFull's returned config differs from the original");
    }

    /** Copy a classpath YAML resource to a temp file so loadRaw gets a real Path. */
    private Path copyTemplateToTemp(Path tempDir, String filename) throws Exception {
        Path target = tempDir.resolve(filename);
        try (InputStream is = PipelineConfigRoundTripTest.class
                .getResourceAsStream("/config/feature.yaml")) {
            assertNotNull(is, "feature.yaml must be on classpath");
            byte[] bytes = is.readAllBytes();
            Files.write(target, bytes);
        }
        // Some envs strip trailing newline — ensure it's UTF-8 and present
        String content = Files.readString(target, StandardCharsets.UTF_8);
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    /** Sanity: writer's toYaml round-trips through Jackson YAML mapper structurally. */
    @Test
    void writerToYamlIsParseable() throws Exception {
        PipelineConfig original = loader.loadRaw(
            copyTemplateToTemp(Files.createTempDirectory("pcr-"), "f.yaml"));
        String yaml = writer.toYaml(original);
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        PipelineConfig reparsed = yamlMapper.readValue(yaml, PipelineConfig.class);
        assertEquals(original.getName(), reparsed.getName());
        assertEquals(original.getPipeline().size(), reparsed.getPipeline().size());
    }
}
