package com.workflow.core;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "pipeline_run")
@NamedEntityGraph(
    name = "PipelineRun.withCompletedBlocks",
    attributeNodes = @NamedAttributeNode("completedBlocks")
)
public class PipelineRun {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String pipelineName;

    @Column(columnDefinition = "TEXT")
    private String requirement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    private String currentBlock;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Instant startedAt;

    private Instant completedAt;

    // LAZY to avoid N+1 queries on list endpoints.
    // Use @EntityGraph or explicit JOIN FETCH when the full collections are needed.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "pipeline_run_completed_blocks", joinColumns = @JoinColumn(name = "run_id"))
    @Column(name = "block_id")
    private Set<String> completedBlocks = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "pipeline_run_auto_approve", joinColumns = @JoinColumn(name = "run_id"))
    @Column(name = "block_id")
    private Set<String> autoApprove = new HashSet<>();

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<BlockOutput> outputs = new ArrayList<>();

    /** Tracks loopback iteration counts. Key: "loopback:{verify_block_id}:{target_block_id}" */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "pipeline_run_loop_iterations", joinColumns = @JoinColumn(name = "run_id"))
    @MapKeyColumn(name = "loop_key")
    @Column(name = "iteration_count")
    private Map<String, Integer> loopIterations = new HashMap<>();

    /** JSON-serialized list of loopback events for status display. */
    @Column(columnDefinition = "TEXT")
    private String loopHistoryJson = "[]";

    /** Set when the run enters PAUSED_FOR_APPROVAL; cleared on resume. */
    private Instant pausedAt;

    /** Snapshot of the current block's {@code timeout} at pause time. Null if no timeout configured. */
    private Integer approvalTimeoutSeconds;

    /** Snapshot of the current block's {@code on_timeout.action} at pause time. Null if no timeout configured. */
    private String approvalTimeoutAction;

    /** Snapshot of the pipeline YAML at run start — guarantees deterministic replay. */
    @Column(columnDefinition = "TEXT")
    private String configSnapshotJson;

    /** Snapshot of skill profiles referenced by this run at start. */
    @Column(columnDefinition = "TEXT")
    private String skillsSnapshotJson;

    /** Dry-run: side-effect blocks (MR, CI, deploy) return mock output without touching external systems. */
    @Column(nullable = false)
    private boolean dryRun = false;

    /** Arbitrary named inputs provided at run start (e.g. task_file, build_command). JSON object. */
    @Column(name = "run_inputs_json", columnDefinition = "TEXT")
    private String runInputsJson;

    /** Project scope — runs created under a given {@code X-Project-Slug}. Defaults to {@code default}. */
    @Column(nullable = false)
    private String projectSlug = "default";

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public PipelineRun() {}

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final PipelineRun run = new PipelineRun();
        public Builder id(UUID id) { run.id = id; return this; }
        public Builder pipelineName(String name) { run.pipelineName = name; return this; }
        public Builder requirement(String req) { run.requirement = req; return this; }
        public Builder status(RunStatus status) { run.status = status; return this; }
        public Builder completedBlocks(Set<String> blocks) { run.completedBlocks = blocks; return this; }
        public Builder autoApprove(Set<String> autoApprove) { run.autoApprove = autoApprove; return this; }
        public Builder outputs(List<BlockOutput> outputs) { run.outputs = outputs; return this; }
        public PipelineRun build() { return run; }
    }

    public Map<String, Integer> getLoopIterations() { return loopIterations; }
    public void setLoopIterations(Map<String, Integer> loopIterations) {
        this.loopIterations = loopIterations != null ? loopIterations : new HashMap<>();
    }

    public String getLoopHistoryJson() { return loopHistoryJson; }
    public void setLoopHistoryJson(String loopHistoryJson) {
        this.loopHistoryJson = loopHistoryJson != null ? loopHistoryJson : "[]";
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPipelineName() { return pipelineName; }
    public void setPipelineName(String pipelineName) { this.pipelineName = pipelineName; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public String getCurrentBlock() { return currentBlock; }
    public void setCurrentBlock(String currentBlock) { this.currentBlock = currentBlock; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Set<String> getCompletedBlocks() { return completedBlocks; }
    public void setCompletedBlocks(Set<String> completedBlocks) { this.completedBlocks = completedBlocks; }
    public Set<String> getAutoApprove() { return autoApprove; }
    public void setAutoApprove(Set<String> autoApprove) { this.autoApprove = autoApprove; }
    public List<BlockOutput> getOutputs() { return outputs; }
    public void setOutputs(List<BlockOutput> outputs) { this.outputs = outputs; }

    public String getRunInputsJson() { return runInputsJson; }
    public void setRunInputsJson(String runInputsJson) { this.runInputsJson = runInputsJson; }

    public Instant getPausedAt() { return pausedAt; }
    public void setPausedAt(Instant pausedAt) { this.pausedAt = pausedAt; }

    public Integer getApprovalTimeoutSeconds() { return approvalTimeoutSeconds; }
    public void setApprovalTimeoutSeconds(Integer approvalTimeoutSeconds) {
        this.approvalTimeoutSeconds = approvalTimeoutSeconds;
    }

    public String getApprovalTimeoutAction() { return approvalTimeoutAction; }
    public void setApprovalTimeoutAction(String approvalTimeoutAction) {
        this.approvalTimeoutAction = approvalTimeoutAction;
    }

    public String getConfigSnapshotJson() { return configSnapshotJson; }
    public void setConfigSnapshotJson(String configSnapshotJson) { this.configSnapshotJson = configSnapshotJson; }

    public String getSkillsSnapshotJson() { return skillsSnapshotJson; }
    public void setSkillsSnapshotJson(String skillsSnapshotJson) { this.skillsSnapshotJson = skillsSnapshotJson; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) {
        this.projectSlug = projectSlug != null ? projectSlug : "default";
    }
}
