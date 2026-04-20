package com.workflow.integrations.tracker;

/**
 * Input for creating a subtask under a parent issue.
 *
 * @param summary      required — short title
 * @param description  required — body/description
 * @param estimate     optional — estimate hint in provider-native form (e.g. "2h", "3d")
 */
public record SubtaskSpec(String summary, String description, String estimate) {
    public SubtaskSpec(String summary, String description) {
        this(summary, description, null);
    }
}
