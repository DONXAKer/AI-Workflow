package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Timeout behavior for a block. Applied when a block has been paused for approval
 * (or running, in a future iteration) longer than {@code BlockConfig.timeoutSeconds}.
 *
 * <p>YAML example:
 * <pre>
 * timeout: 3600            # 1h
 * on_timeout:
 *   action: escalate        # fail | notify | escalate
 *   target: release_manager # role name for escalate; free-form
 *   description: "Поднять release manager если задача зависла"
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TimeoutConfig {

    public enum Action {
        FAIL("fail"),
        NOTIFY("notify"),
        ESCALATE("escalate");

        private final String value;

        Action(String value) { this.value = value; }

        @JsonValue
        public String getValue() { return value; }

        @JsonCreator
        public static Action fromValue(String v) {
            if (v == null) return null;
            String n = v.trim().toLowerCase();
            for (Action a : values()) if (a.value.equals(n)) return a;
            throw new IllegalArgumentException("Unknown timeout action: " + v);
        }
    }

    private Action action = Action.NOTIFY;
    private String target;
    private String description;

    @JsonProperty("action")
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action != null ? action : Action.NOTIFY; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
