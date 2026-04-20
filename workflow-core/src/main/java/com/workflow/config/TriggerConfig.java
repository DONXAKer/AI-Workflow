package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Declarative trigger for auto-starting a pipeline run from an external event (webhook).
 *
 * <p>YAML example:
 * <pre>
 * triggers:
 *   - id: youtrack_ready_for_dev
 *     type: webhook
 *     provider: youtrack
 *     event: status_change
 *     conditions:
 *       new_status: "Ready for Dev"
 *     entry_point_id: from_tracker_issue
 * </pre>
 *
 * <p>The webhook controller matches an incoming event against all configured triggers
 * and starts runs for every match.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TriggerConfig {

    private String id;
    private String type = "webhook";
    private String provider;
    private String event;

    @JsonProperty("conditions")
    private Map<String, String> conditions = new HashMap<>();

    @JsonProperty("entry_point_id")
    private String entryPointId;

    @JsonProperty("from_block")
    private String fromBlock;

    private boolean enabled = true;

    public TriggerConfig() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public Map<String, String> getConditions() { return conditions; }
    public void setConditions(Map<String, String> conditions) {
        this.conditions = conditions != null ? conditions : new HashMap<>();
    }

    public String getEntryPointId() { return entryPointId; }
    public void setEntryPointId(String entryPointId) { this.entryPointId = entryPointId; }

    public String getFromBlock() { return fromBlock; }
    public void setFromBlock(String fromBlock) { this.fromBlock = fromBlock; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
