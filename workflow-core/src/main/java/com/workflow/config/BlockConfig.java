package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockConfig {

    private String id;
    private String block;
    private AgentConfig agent;

    @JsonProperty("approval")
    private boolean approval = true;

    @JsonProperty("approval_mode")
    private ApprovalMode approvalMode;

    private boolean enabled = true;

    @JsonProperty("depends_on")
    private List<String> dependsOn = new ArrayList<>();

    private Map<String, Object> config = new HashMap<>();

    private VerifyConfig verify;

    private String condition;

    @JsonProperty("on_failure")
    private OnFailureConfig onFailure;

    private List<String> skills = new ArrayList<>();

    private String profile;

    @JsonProperty("required_gates")
    private List<GateConfig> requiredGates = new ArrayList<>();

    @JsonProperty("timeout")
    private Integer timeoutSeconds;

    @JsonProperty("on_timeout")
    private TimeoutConfig onTimeout;

    @JsonProperty("retry")
    private RetryConfig retry;

    /**
     * Per-instance phase override. {@code null} = use the block type's default
     * phase from {@link com.workflow.blocks.BlockMetadata#phase()}. Set to one
     * of the {@link com.workflow.blocks.Phase} values (case-insensitive) to pin
     * a polymorphic block (shell_exec, http_get, orchestrator) or to repurpose
     * a monomorphic block (e.g. a {@code build} block playing the release-build
     * role rather than verify-build).
     */
    @JsonProperty("phase")
    private String phase;

    public BlockConfig() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBlock() {
        return block;
    }

    public void setBlock(String block) {
        this.block = block;
    }

    public AgentConfig getAgent() {
        return agent;
    }

    public void setAgent(AgentConfig agent) {
        this.agent = agent;
    }

    public boolean isApproval() {
        return approval;
    }

    public void setApproval(boolean approval) {
        this.approval = approval;
    }

    public ApprovalMode getApprovalMode() {
        return approvalMode;
    }

    public void setApprovalMode(ApprovalMode approvalMode) {
        this.approvalMode = approvalMode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Canonical approval decision: prefers explicit {@code approval_mode} when set,
     * otherwise derives from the legacy boolean {@code approval} flag
     * ({@code true} → MANUAL, {@code false} → AUTO). Not serialised — derived from
     * {@link #approvalMode} and {@link #approval}.
     */
    @JsonIgnore
    public ApprovalMode getEffectiveApprovalMode() {
        if (approvalMode != null) return approvalMode;
        return approval ? ApprovalMode.MANUAL : ApprovalMode.AUTO;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>();
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config != null ? new HashMap<>(config) : new HashMap<>();
    }

    public VerifyConfig getVerify() { return verify; }
    public void setVerify(VerifyConfig verify) { this.verify = verify; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public OnFailureConfig getOnFailure() { return onFailure; }
    public void setOnFailure(OnFailureConfig onFailure) { this.onFailure = onFailure; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills != null ? skills : new ArrayList<>(); }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }

    public List<GateConfig> getRequiredGates() { return requiredGates; }
    public void setRequiredGates(List<GateConfig> requiredGates) {
        this.requiredGates = requiredGates != null ? requiredGates : new ArrayList<>();
    }

    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public TimeoutConfig getOnTimeout() { return onTimeout; }
    public void setOnTimeout(TimeoutConfig onTimeout) { this.onTimeout = onTimeout; }

    public RetryConfig getRetry() { return retry; }
    public void setRetry(RetryConfig retry) { this.retry = retry; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    /**
     * Returns a copy of this BlockConfig with a merged config map.
     */
    public BlockConfig withMergedConfig(Map<String, Object> extraConfig) {
        BlockConfig copy = new BlockConfig();
        copy.setId(this.id);
        copy.setBlock(this.block);
        copy.setAgent(this.agent);
        copy.setApproval(this.approval);
        copy.setApprovalMode(this.approvalMode);
        copy.setEnabled(this.enabled);
        copy.setDependsOn(new ArrayList<>(this.dependsOn));
        copy.setVerify(this.verify);
        copy.setCondition(this.condition);
        copy.setOnFailure(this.onFailure);
        copy.setSkills(new ArrayList<>(this.skills));
        copy.setProfile(this.profile);
        copy.setRequiredGates(new ArrayList<>(this.requiredGates));
        copy.setTimeoutSeconds(this.timeoutSeconds);
        copy.setOnTimeout(this.onTimeout);
        copy.setRetry(this.retry);
        copy.setPhase(this.phase);
        Map<String, Object> mergedConfig = new HashMap<>(this.config);
        mergedConfig.putAll(extraConfig);
        copy.setConfig(mergedConfig);
        return copy;
    }
}
