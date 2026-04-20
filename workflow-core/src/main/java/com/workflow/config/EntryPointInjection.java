package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/** One injected artifact for a named entry point. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EntryPointInjection {

    @JsonProperty("block_id")
    private String blockId;

    /** youtrack | gitlab_branch | gitlab_mr | github_branch | github_pr | empty */
    private String source;

    private Map<String, Object> config = new HashMap<>();

    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) {
        this.config = config != null ? config : new HashMap<>();
    }
}
