package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PipelineConfig {

    private String name;
    private String description;
    private DefaultsConfig defaults;
    private IntegrationsConfig integrations;

    // Canonical wire form is camelCase (matches new feature.yaml). Legacy
    // snake_case "knowledge_base" still accepted on deserialization.
    @JsonAlias({"knowledge_base"})
    private KnowledgeBaseConfig knowledgeBase;

    private List<BlockConfig> pipeline = new ArrayList<>();

    @JsonProperty("entry_points")
    private List<EntryPointConfig> entryPoints = new ArrayList<>();

    private List<TriggerConfig> triggers = new ArrayList<>();

    public PipelineConfig() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public DefaultsConfig getDefaults() {
        return defaults;
    }

    public void setDefaults(DefaultsConfig defaults) {
        this.defaults = defaults;
    }

    public IntegrationsConfig getIntegrations() {
        return integrations;
    }

    public void setIntegrations(IntegrationsConfig integrations) {
        this.integrations = integrations;
    }

    public KnowledgeBaseConfig getKnowledgeBase() {
        return knowledgeBase;
    }

    public void setKnowledgeBase(KnowledgeBaseConfig knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public List<BlockConfig> getPipeline() {
        return pipeline;
    }

    public void setPipeline(List<BlockConfig> pipeline) {
        this.pipeline = pipeline != null ? pipeline : new ArrayList<>();
    }

    public List<EntryPointConfig> getEntryPoints() {
        return entryPoints;
    }

    public void setEntryPoints(List<EntryPointConfig> entryPoints) {
        this.entryPoints = entryPoints != null ? entryPoints : new ArrayList<>();
    }

    public List<TriggerConfig> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<TriggerConfig> triggers) {
        this.triggers = triggers != null ? triggers : new ArrayList<>();
    }
}
