package com.workflow.model;

import com.workflow.security.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "integration_config")
public class IntegrationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntegrationType type;

    private String displayName;
    private String baseUrl;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String token;

    private String project;
    private String owner;
    private String repo;

    @Column(columnDefinition = "TEXT")
    private String extraConfigJson;

    @Column(nullable = false)
    private boolean isDefault;

    /** Project scope — integrations created under a given {@code X-Project-Slug}. */
    @Column(nullable = false)
    private String projectSlug = "default";

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public IntegrationType getType() { return type; }
    public void setType(IntegrationType type) { this.type = type; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getExtraConfigJson() { return extraConfigJson; }
    public void setExtraConfigJson(String extraConfigJson) { this.extraConfigJson = extraConfigJson; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) {
        this.projectSlug = projectSlug != null ? projectSlug : "default";
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
