package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** LLM-based quality evaluation for the verify block. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LLMCheckConfig {

    private boolean enabled = true;
    private String prompt;

    // Canonical key is "minScore" (camelCase, the field name); accept legacy
    // snake_case "min_score" on read so existing yamls keep working.
    @JsonAlias({"min_score"})
    private double minScore = 7.0;

    private String model;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
