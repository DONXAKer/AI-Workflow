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

@Service
public class PipelineConfigWriter {

    private final ObjectMapper yamlMapper;
    private final PipelineConfigLoader loader;
    private final PipelineConfigValidator validator;

    public PipelineConfigWriter(PipelineConfigLoader loader, PipelineConfigValidator validator) {
        this.loader = loader;
        this.validator = validator;
        YAMLFactory yamlFactory = new YAMLFactory();
        yamlFactory.configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false);
        this.yamlMapper = new ObjectMapper(yamlFactory);
        this.yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.yamlMapper.registerModule(new JavaTimeModule());
        this.yamlMapper.findAndRegisterModules();
    }

    /**
     * Writes the full PipelineConfig as-is to {@code yamlPath}. Pre-validates the
     * incoming config; if invalid, throws {@link InvalidPipelineException} BEFORE
     * touching the disk, so a bad payload from the editor never replaces the existing
     * file. Returns the parsed, env-expanded config that ended up on disk.
     *
     * <p>This is the canonical save path used by the Pipeline Editor — it sends the
     * full config and expects an atomic-ish save with structured validation errors on
     * a 400 response (handled by the caller).
     */
    public PipelineConfig writeFull(Path yamlPath, PipelineConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("PipelineConfig must not be null");
        }
        // Pre-write validation: refuse to write an invalid config.
        ValidationResult preCheck = validator.validate(config);
        if (!preCheck.valid()) {
            throw new InvalidPipelineException(preCheck);
        }

        String yaml = yamlMapper.writeValueAsString(config);
        Files.writeString(yamlPath, yaml, StandardCharsets.UTF_8);

        // Re-parse with env-expansion to confirm bytes-on-disk round-trip.
        PipelineConfig parsed = loader.load(yamlPath);
        ValidationResult result = validator.validate(parsed);
        if (!result.valid()) {
            throw new InvalidPipelineException(result);
        }
        return parsed;
    }

    /** Exposed for tests: serialise to YAML without writing or validating. */
    public String toYaml(PipelineConfig config) throws IOException {
        return yamlMapper.writeValueAsString(config);
    }
}
