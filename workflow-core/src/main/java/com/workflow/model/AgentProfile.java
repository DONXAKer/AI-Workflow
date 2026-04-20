package com.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "agent_profile")
public class AgentProfile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String rolePrompt;

    @Column(columnDefinition = "TEXT")
    private String customPrompt;

    private String model;

    private Integer maxTokens;

    private Double temperature;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String skillsJson;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String knowledgeSourcesJson;

    @Column(nullable = false)
    private boolean useExamples = false;

    private String recommendedPreset;

    /** True for built-in skills shipped in the repo; false for operator-created via UI. */
    @Column(nullable = false)
    private boolean builtin = false;

    /** Project scope. Built-in profiles use "default" and are visible cross-project as fallback. */
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

    // --- skills JSON <-> List<String> ---

    @Transient
    @JsonProperty("skills")
    public List<String> getSkillNames() {
        if (skillsJson == null || skillsJson.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(skillsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @JsonProperty("skills")
    public void setSkillNames(List<String> skills) {
        try {
            this.skillsJson = (skills == null || skills.isEmpty()) ? "[]" : MAPPER.writeValueAsString(skills);
        } catch (Exception e) {
            this.skillsJson = "[]";
        }
    }

    // --- getters / setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRolePrompt() { return rolePrompt; }
    public void setRolePrompt(String rolePrompt) { this.rolePrompt = rolePrompt; }
    public String getCustomPrompt() { return customPrompt; }
    public void setCustomPrompt(String customPrompt) { this.customPrompt = customPrompt; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public String getSkillsJson() { return skillsJson; }
    public void setSkillsJson(String skillsJson) { this.skillsJson = skillsJson; }

    @Transient
    @JsonProperty("knowledgeSources")
    public List<String> getKnowledgeSources() {
        if (knowledgeSourcesJson == null || knowledgeSourcesJson.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(knowledgeSourcesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @JsonProperty("knowledgeSources")
    public void setKnowledgeSources(List<String> sources) {
        try {
            this.knowledgeSourcesJson = (sources == null || sources.isEmpty()) ? "[]" : MAPPER.writeValueAsString(sources);
        } catch (Exception e) {
            this.knowledgeSourcesJson = "[]";
        }
    }

    public String getKnowledgeSourcesJson() { return knowledgeSourcesJson; }
    public void setKnowledgeSourcesJson(String json) { this.knowledgeSourcesJson = json; }

    public boolean isUseExamples() { return useExamples; }
    public void setUseExamples(boolean useExamples) { this.useExamples = useExamples; }

    public String getRecommendedPreset() { return recommendedPreset; }
    public void setRecommendedPreset(String recommendedPreset) { this.recommendedPreset = recommendedPreset; }

    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) {
        this.projectSlug = projectSlug != null ? projectSlug : "default";
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
