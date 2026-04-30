package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfig {

    private String model;

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

    public AgentConfig() {}

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
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

    /** Returns maxTokens with a fallback default. */
    public int getMaxTokensOrDefault() {
        return maxTokens != null ? maxTokens : 8192;
    }

    /** Returns temperature with a fallback default. */
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
