package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeBaseConfig {

    private List<KnowledgeSourceConfig> sources = new ArrayList<>();

    public KnowledgeBaseConfig() {}

    public List<KnowledgeSourceConfig> getSources() {
        return sources;
    }

    public void setSources(List<KnowledgeSourceConfig> sources) {
        this.sources = sources != null ? sources : new ArrayList<>();
    }
}
