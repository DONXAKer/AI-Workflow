package com.workflow.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only log of successful deployments. Written by {@code DeployBlock} on
 * {@code status=success} and read by {@code RollbackBlock} to resolve the previous
 * stable {@code artifact_id} for a given environment.
 *
 * <p>Indexed by {@code (project_slug, environment, deployed_at DESC)} so the typical
 * "what was the deploy before this one?" query stays cheap as the table grows.
 */
@Entity
@Table(
    name = "deployment_history",
    indexes = {
        @Index(name = "idx_deployhist_proj_env_time",
            columnList = "project_slug, environment, deployed_at")
    }
)
public class DeploymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Project-scoped lookups — null means the legacy single-project install. */
    @Column(name = "project_slug")
    private String projectSlug;

    /** Run that produced this deployment — for audit / drill-through to logs. */
    @Column(name = "run_id")
    private UUID runId;

    /** Block id within the run (typically {@code deploy} or {@code deploy_staging}). */
    @Column(name = "block_id")
    private String blockId;

    @Column(name = "environment", nullable = false)
    private String environment;

    /** Full artifact reference (e.g. {@code docker.io/acme/api:1.2.3-abc12345}). */
    @Column(name = "artifact_id", nullable = false)
    private String artifactId;

    /** Optional separate version when {@code artifact_id} is just a name. */
    @Column(name = "artifact_version")
    private String artifactVersion;

    /** Optional URL the operator clicks to inspect this deployment (Grafana / k8s dashboard). */
    @Column(name = "deployment_url")
    private String deploymentUrl;

    /** Status as reported by DeployBlock — always "success" today, kept for future "failed" rows. */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    @PrePersist
    void onCreate() {
        if (deployedAt == null) deployedAt = Instant.now();
        if (status == null) status = "success";
    }

    public Long getId() { return id; }
    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) { this.projectSlug = projectSlug; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public String getArtifactVersion() { return artifactVersion; }
    public void setArtifactVersion(String artifactVersion) { this.artifactVersion = artifactVersion; }
    public String getDeploymentUrl() { return deploymentUrl; }
    public void setDeploymentUrl(String deploymentUrl) { this.deploymentUrl = deploymentUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getDeployedAt() { return deployedAt; }
    public void setDeployedAt(Instant deployedAt) { this.deployedAt = deployedAt; }
}
