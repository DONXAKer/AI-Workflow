package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** A named entry point describing where to start the pipeline. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EntryPointConfig {

    private String id;
    private String name;
    private String description = "";

    @JsonProperty("from_block")
    private String fromBlock;

    private List<EntryPointInjection> inject = new ArrayList<>();

    /** youtrack_subtasks | gitlab_branch | gitlab_mr | github_branch | github_pr */
    @JsonProperty("auto_detect")
    private String autoDetect;

    /** requirement | youtrack_issue | none */
    @JsonProperty("requires_input")
    private String requiresInput = "requirement";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description != null ? description : ""; }

    public String getFromBlock() { return fromBlock; }
    public void setFromBlock(String fromBlock) { this.fromBlock = fromBlock; }

    public List<EntryPointInjection> getInject() { return inject; }
    public void setInject(List<EntryPointInjection> inject) {
        this.inject = inject != null ? inject : new ArrayList<>();
    }

    public String getAutoDetect() { return autoDetect; }
    public void setAutoDetect(String autoDetect) { this.autoDetect = autoDetect; }

    public String getRequiresInput() { return requiresInput; }
    public void setRequiresInput(String requiresInput) { this.requiresInput = requiresInput; }
}
