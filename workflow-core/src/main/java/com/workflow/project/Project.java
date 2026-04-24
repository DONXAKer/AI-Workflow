package com.workflow.project;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Top-level organizational container for runs, integrations, skills, and pipeline configs.
 *
 * <p>MVP has a single {@link #DEFAULT_SLUG} project auto-created on boot. Full scoping of
 * existing entities (adding a {@code project_id} FK to runs, integrations, audit entries)
 * is a follow-up migration that will land with the UI project switcher.
 */
@Entity
@Table(name = "project")
public class Project {

    public static final String DEFAULT_SLUG = "default";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String slug;

    @Column(nullable = false)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Path to this project's pipeline config directory, relative to workflow.config-dir. */
    private String configDir;

    /**
     * Absolute filesystem path to the project's source tree on this host. Tools scoped
     * to a project use this as the root for {@link com.workflow.tools.PathScope} — any
     * path resolution that escapes this root is rejected.
     *
     * <p>Null for projects that don't represent a local checkout (e.g. metadata-only
     * project rows). Block YAML may supply {@code working_dir} inline as a fallback.
     */
    private String workingDir;

    /** Enable orchestrator blocks for this project (default true). */
    @Column(name = "orchestrator_enabled")
    private Boolean orchestratorEnabled;

    /** Default model for orchestrator blocks; null means use block-level agent.model. */
    @Column(name = "orchestrator_model")
    private String orchestratorModel;

    /** Project-specific context injected into every orchestrator system prompt. */
    @Column(name = "orchestrator_system_prompt_extra", columnDefinition = "TEXT")
    private String orchestratorSystemPromptExtra;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
    public boolean isOrchestratorEnabled() { return orchestratorEnabled == null || orchestratorEnabled; }
    public void setOrchestratorEnabled(Boolean orchestratorEnabled) { this.orchestratorEnabled = orchestratorEnabled; }
    public String getOrchestratorModel() { return orchestratorModel; }
    public void setOrchestratorModel(String orchestratorModel) { this.orchestratorModel = orchestratorModel; }
    public String getOrchestratorSystemPromptExtra() { return orchestratorSystemPromptExtra; }
    public void setOrchestratorSystemPromptExtra(String orchestratorSystemPromptExtra) { this.orchestratorSystemPromptExtra = orchestratorSystemPromptExtra; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
