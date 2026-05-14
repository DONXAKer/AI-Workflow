package com.workflow.preflight;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Cached result of a preflight check: {@code gradle build} + {@code gradle test}
 * (or per-project equivalents) executed against a specific {@code main} commit.
 *
 * <p>Lookup key is {@code (projectSlug, mainCommitSha, configHash)} where
 * {@code configHash} = SHA-256 of {@code build_cmd + test_cmd + fqn_format}. A
 * snapshot expires by TTL (default 7 days, configurable via
 * {@code workflow.preflight.cache-ttl}) and may be invalidated manually via
 * the project-level refresh API.
 *
 * <p>{@code baselineFailuresJson} stores the list of pre-existing failed test
 * FQNs so downstream {@code verify_code}/{@code ci} blocks can compute delta:
 * "tests we broke" = current failures \\ baseline failures.
 */
@Entity
@Table(name = "preflight_snapshot", indexes = {
        @Index(name = "idx_preflight_lookup", columnList = "project_slug,main_commit_sha,config_hash"),
        @Index(name = "idx_preflight_created", columnList = "created_at")
})
public class PreflightSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_slug", nullable = false)
    private String projectSlug;

    @Column(name = "main_commit_sha", nullable = false, length = 64)
    private String mainCommitSha;

    /** SHA-256 of the resolved preflight config (build_cmd + test_cmd + fqn_format). */
    @Column(name = "config_hash", nullable = false, length = 64)
    private String configHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PreflightStatus status;

    @Column(name = "build_ok")
    private boolean buildOk;

    @Column(name = "test_ok")
    private boolean testOk;

    /** JSON array of FQN strings — pre-existing failing tests on main. */
    @Column(name = "baseline_failures_json", columnDefinition = "TEXT")
    private String baselineFailuresJson;

    /** Last few KB of build/test stdout for debugging. Kept short to avoid bloat. */
    @Column(name = "log_excerpt", columnDefinition = "TEXT")
    private String logExcerpt;

    @Column(name = "build_cmd", length = 1024)
    private String buildCmd;

    @Column(name = "test_cmd", length = 1024)
    private String testCmd;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) { this.projectSlug = projectSlug; }
    public String getMainCommitSha() { return mainCommitSha; }
    public void setMainCommitSha(String mainCommitSha) { this.mainCommitSha = mainCommitSha; }
    public String getConfigHash() { return configHash; }
    public void setConfigHash(String configHash) { this.configHash = configHash; }
    public PreflightStatus getStatus() { return status; }
    public void setStatus(PreflightStatus status) { this.status = status; }
    public boolean isBuildOk() { return buildOk; }
    public void setBuildOk(boolean buildOk) { this.buildOk = buildOk; }
    public boolean isTestOk() { return testOk; }
    public void setTestOk(boolean testOk) { this.testOk = testOk; }
    public String getBaselineFailuresJson() { return baselineFailuresJson; }
    public void setBaselineFailuresJson(String baselineFailuresJson) { this.baselineFailuresJson = baselineFailuresJson; }
    public String getLogExcerpt() { return logExcerpt; }
    public void setLogExcerpt(String logExcerpt) { this.logExcerpt = logExcerpt; }
    public String getBuildCmd() { return buildCmd; }
    public void setBuildCmd(String buildCmd) { this.buildCmd = buildCmd; }
    public String getTestCmd() { return testCmd; }
    public void setTestCmd(String testCmd) { this.testCmd = testCmd; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
