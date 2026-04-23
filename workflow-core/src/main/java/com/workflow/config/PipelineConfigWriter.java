package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PipelineConfigWriter {

    private final ObjectMapper yamlMapper;
    private final PipelineConfigLoader loader;

    public PipelineConfigWriter(PipelineConfigLoader loader) {
        this.loader = loader;
        YAMLFactory yamlFactory = new YAMLFactory();
        yamlFactory.configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false);
        this.yamlMapper = new ObjectMapper(yamlFactory);
        this.yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.yamlMapper.registerModule(new JavaTimeModule());
        this.yamlMapper.findAndRegisterModules();
    }

    /**
     * Applies block-level overrides + new defaults to the YAML file and writes it back.
     * Validates by re-parsing after write. Returns the validated (env-expanded) config.
     */
    public PipelineConfig applyAndWrite(Path yamlPath,
                                        DefaultsConfig newDefaults,
                                        List<BlockSettingDto> blockSettings) throws IOException {
        PipelineConfig config = loader.loadRaw(yamlPath);

        config.setDefaults(newDefaults);

        if (blockSettings != null) {
            Map<String, BlockConfig> index = config.getPipeline().stream()
                .collect(Collectors.toMap(BlockConfig::getId, Function.identity()));

            for (BlockSettingDto s : blockSettings) {
                BlockConfig block = index.get(s.getId());
                if (block == null) continue;

                block.setEnabled(s.isEnabled());
                block.setApproval(s.isApproval());
                block.setProfile(nullIfBlank(s.getProfile()));
                block.setSkills(s.getSkills() != null ? s.getSkills() : List.of());

                AgentConfig ag = toAgentConfig(s.getAgent());
                block.setAgent(ag);
            }
        }

        String yaml = yamlMapper.writeValueAsString(config);
        Files.writeString(yamlPath, yaml, StandardCharsets.UTF_8);

        // Re-parse with env expansion to validate
        return loader.load(yamlPath);
    }

    private AgentConfig toAgentConfig(BlockSettingDto.AgentOverride src) {
        if (src == null) return null;
        boolean hasModel = src.getModel() != null && !src.getModel().isBlank();
        boolean hasTokens = src.getMaxTokens() != null;
        boolean hasTemp = src.getTemperature() != null;
        boolean hasPrompt = src.getSystemPrompt() != null && !src.getSystemPrompt().isBlank();
        if (!hasModel && !hasTokens && !hasTemp && !hasPrompt) return null;

        AgentConfig ag = new AgentConfig();
        if (hasModel) ag.setModel(src.getModel());
        if (hasTokens) ag.setMaxTokens(src.getMaxTokens());
        if (hasTemp) ag.setTemperature(src.getTemperature());
        if (hasPrompt) ag.setSystemPrompt(src.getSystemPrompt());
        return ag;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
