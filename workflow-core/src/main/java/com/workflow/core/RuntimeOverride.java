package com.workflow.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-block override applied by escalation cloud-steps. Stored as a value in the
 * {@code PipelineRun.runtimeOverridesJson} map keyed by block id.
 *
 * <p>Both fields are nullable: {@link #provider()} swaps the LLM routing layer
 * (e.g. {@code "OPENROUTER"}); {@link #model()} swaps the preset/tier or full
 * model id (e.g. {@code "smart"} or {@code "z-ai/glm-4.6"}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuntimeOverride(String provider, String model) {

    @JsonCreator
    public RuntimeOverride(@JsonProperty("provider") String provider,
                           @JsonProperty("model") String model) {
        this.provider = (provider != null && !provider.isBlank()) ? provider : null;
        this.model = (model != null && !model.isBlank()) ? model : null;
    }

    public boolean isEmpty() { return provider == null && model == null; }
}
