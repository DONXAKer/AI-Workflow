package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfig {

    private String model;

    /**
     * Symbolic tier (e.g. {@code smart}, {@code flash}) resolved through
     * {@link com.workflow.llm.ModelPresetResolver} to a concrete model id.
     * Used when {@link #model} is not set explicitly. Lets pipelines say
     * "this block is an analyst" or "this block is an executor" without
     * binding to a specific model — swap the global preset to upgrade everywhere.
     */
    private String tier;

    // Canonical key on both read and write is camelCase (matches existing
    // feature.yaml). Aliases accept snake_case and the legacy "*OrDefault" form
    // some yamls use, so we never lose data on round-trip.
    @JsonProperty("systemPrompt")
    @JsonAlias({"system_prompt"})
    private String systemPrompt;

    @JsonProperty("maxTokens")
    @JsonAlias({"max_tokens", "maxTokensOrDefault"})
    private Integer maxTokens;

    @JsonAlias({"temperatureOrDefault"})
    private Double temperature;

    @JsonProperty("promptContextAllow")
    @JsonAlias({"prompt_context_allow"})
    private List<String> promptContextAllow;

    @JsonProperty("completionSignal")
    @JsonAlias({"completion_signal"})
    private String completionSignal;

    public AgentConfig() {}

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    /**
     * Effective model identifier: explicit {@link #model} wins, otherwise falls back
     * to the symbolic {@link #tier}. Returns null if neither is set — caller is then
     * responsible for applying its own default. Not serialised — derived from
     * {@link #model} and {@link #tier}.
     */
    @JsonIgnore
    public String getEffectiveModel() {
        if (model != null && !model.isBlank()) return model;
        if (tier != null && !tier.isBlank()) return tier;
        return null;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public List<String> getPromptContextAllow() {
        return promptContextAllow;
    }

    public void setPromptContextAllow(List<String> promptContextAllow) {
        this.promptContextAllow = promptContextAllow;
    }

    public String getCompletionSignal() {
        return completionSignal;
    }

    public void setCompletionSignal(String completionSignal) {
        this.completionSignal = completionSignal;
    }

    /** Returns maxTokens with a fallback default. Not serialised — derived from {@link #maxTokens}. */
    @JsonIgnore
    public int getMaxTokensOrDefault() {
        return maxTokens != null ? maxTokens : 8192;
    }

    /** Returns temperature with a fallback default. Not serialised — derived from {@link #temperature}. */
    @JsonIgnore
    public double getTemperatureOrDefault() {
        return temperature != null ? temperature : 1.0;
    }

    /**
     * Composes the effective system prompt in the correct order:
     * built-in header (role + best practices) → YAML project context → built-in footer (output contract + quality bar).
     * If yamlPrompt is absent, header and footer are joined directly.
     */
    public static String buildSystemPrompt(String header, String yamlPrompt, String footer) {
        StringBuilder sb = new StringBuilder(header);
        if (yamlPrompt != null && !yamlPrompt.isBlank()) {
            sb.append("\n\n## Project Context\n").append(yamlPrompt.strip());
        }
        if (footer != null && !footer.isBlank()) {
            sb.append("\n\n").append(footer.strip());
        }
        return sb.toString();
    }
}
