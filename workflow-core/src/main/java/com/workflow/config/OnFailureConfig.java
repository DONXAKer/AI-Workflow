package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Declarative failure handling for CI/CD blocks (gitlab_ci, github_actions). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OnFailureConfig {

    /** loopback | fail | warn | skip */
    private String action = "fail";

    /** Block id to loop back to when action=loopback. */
    private String target;

    @JsonProperty("max_iterations")
    private int maxIterations = 3;

    @JsonProperty("inject_context")
    private Map<String, String> injectContext = new HashMap<>();

    @JsonProperty("failed_statuses")
    private List<String> failedStatuses = Arrays.asList("failure", "failed", "timeout", "cancelled");

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public Map<String, String> getInjectContext() { return injectContext; }
    public void setInjectContext(Map<String, String> injectContext) {
        this.injectContext = injectContext != null ? injectContext : new HashMap<>();
    }

    public List<String> getFailedStatuses() { return failedStatuses; }
    public void setFailedStatuses(List<String> failedStatuses) {
        this.failedStatuses = failedStatuses != null ? failedStatuses : this.failedStatuses;
    }
}
