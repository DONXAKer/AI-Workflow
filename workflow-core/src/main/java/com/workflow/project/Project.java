package com.workflow.project;

import com.workflow.account.TenantContext;
import com.workflow.llm.LlmProvider;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

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
@Filter(name = "accountFilter")
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

    /**
     * Technology stack as JSON array: [{name, version}, ...].
     * Example: [{"name":"java","version":"21"},{"name":"spring-boot","version":"3.5"}]
     * Used by TechStackPromptEnricher to inject tech context into block system prompts at run time.
     */
    @Column(name = "tech_stack_json", columnDefinition = "TEXT")
    private String techStackJson;

    /**
     * Default LLM provider used when a run is started without an explicit
     * {@code inputs.provider}. Pipeline blocks gated with
     * {@code condition: "$.input.provider == 'CLAUDE_CODE_CLI'"} switch on this.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_provider")
    private LlmProvider defaultProvider;

    /**
     * Default task tracker provider for this project. Used by tracker blocks when
     * {@code config.provider} is not set explicitly in the pipeline YAML.
     * Supported values: {@code youtrack}, {@code jira}, {@code github}, {@code gitlab}, {@code linear}.
     */
    @Column(name = "default_tracker_provider")
    private String defaultTrackerProvider;

    /**
     * Relative path inside workingDir where task .md files live.
     * Defaults to {@code "tasks/active"} when null.
     */
    @Column(name = "tasks_dir")
    private String tasksDir;

    /**
     * Per-project override for the global escalation ladder. JSON array of polymorphic
     * {@link com.workflow.config.EscalationStep} objects discriminated by {@code tier}.
     * When null, blocks fall back to {@code workflow.escalation.defaults} in application.yaml.
     * Block-level {@code escalation: [...]} in pipeline YAML overrides both.
     */
    @Column(name = "escalation_defaults_json", columnDefinition = "TEXT")
    private String escalationDefaultsJson;

    /** Owning account (multi-tenancy). Nullable for pre-migration rows; backfilled at startup. */
    @Column(name = "account_id")
    private Long accountId;

    /**
     * Remote repository URL (HTTPS). When set, each run clones it into an isolated
     * per-run sandbox via {@code WorkspaceProvisioner} instead of using {@link #workingDir}.
     * Null for self-hosted / power-user projects that point at a local checkout.
     */
    @Column(name = "repo_url")
    private String repoUrl;

    /** {@code github} or {@code gitlab} — drives which integration config is auto-created. */
    @Column(name = "repo_provider")
    private String repoProvider;

    /** Branch the per-run sandbox is checked out at; null clones the remote default branch. */
    @Column(name = "default_branch")
    private String defaultBranch;

    /** Access token used to clone {@link #repoUrl}. Encrypted at rest. */
    @Convert(converter = com.workflow.security.EncryptedStringConverter.class)
    @Column(name = "repo_token", columnDefinition = "TEXT")
    private String repoToken;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (accountId == null) {
            Long tenant = TenantContext.get();
            if (tenant != null) accountId = tenant;
        }
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
    public String getTechStackJson() { return techStackJson; }
    public void setTechStackJson(String techStackJson) { this.techStackJson = techStackJson; }
    public LlmProvider getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(LlmProvider defaultProvider) { this.defaultProvider = defaultProvider; }
    /** Returns the configured provider or {@link LlmProvider#OPENROUTER} when unset. */
    public LlmProvider getEffectiveDefaultProvider() {
        return defaultProvider == null ? LlmProvider.OPENROUTER : defaultProvider;
    }
    public String getDefaultTrackerProvider() { return defaultTrackerProvider; }
    public void setDefaultTrackerProvider(String defaultTrackerProvider) { this.defaultTrackerProvider = defaultTrackerProvider; }
    /** Returns the configured tracker provider or {@code "youtrack"} when unset. */
    public String getEffectiveDefaultTrackerProvider() {
        return defaultTrackerProvider != null && !defaultTrackerProvider.isBlank() ? defaultTrackerProvider : "youtrack";
    }
    public String getTasksDir() { return tasksDir; }
    public void setTasksDir(String tasksDir) { this.tasksDir = tasksDir; }
    /** Returns configured tasksDir or {@code "tasks/active"} when unset. */
    public String getEffectiveTasksDir() {
        return tasksDir != null && !tasksDir.isBlank() ? tasksDir : "tasks/active";
    }
    public String getEscalationDefaultsJson() { return escalationDefaultsJson; }
    public void setEscalationDefaultsJson(String escalationDefaultsJson) { this.escalationDefaultsJson = escalationDefaultsJson; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getRepoProvider() { return repoProvider; }
    public void setRepoProvider(String repoProvider) { this.repoProvider = repoProvider; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getRepoToken() { return repoToken; }
    public void setRepoToken(String repoToken) { this.repoToken = repoToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
