package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/** What to do when a verify block fails. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OnFailConfig {

    /** loopback | fail | warn */
    private String action = "fail";

    /** Block id to loop back to when action=loopback. */
    private String target = "";

    @JsonProperty("max_iterations")
    private int maxIterations = 3;

    /** JSONPath-like references: {"key": "$.block_id.field"} */
    @JsonProperty("inject_context")
    private Map<String, String> injectContext = new HashMap<>();

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target != null ? target : ""; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public Map<String, String> getInjectContext() { return injectContext; }
    public void setInjectContext(Map<String, String> injectContext) {
        this.injectContext = injectContext != null ? injectContext : new HashMap<>();
    }
}
