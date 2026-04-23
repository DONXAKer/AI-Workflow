package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfig {

    private String model;

    @JsonProperty("system_prompt")
    private String systemPrompt;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

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
}
