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
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
