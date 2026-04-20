package com.workflow.integrations.tracker;

import java.util.Map;

/**
 * Provider-agnostic representation of a task tracker issue.
 *
 * @param id           internal ID used by the provider (e.g. YouTrack "2-123", Jira "PROJ-42")
 * @param readableId   human-readable ID (e.g. "PROJ-42") — for YouTrack equals idReadable
 * @param summary      short title
 * @param description  full body/description
 * @param status       current status/state name
 * @param url          web URL to view the issue
 * @param customFields provider-specific fields passed through verbatim
 */
public record TaskIssue(
    String id,
    String readableId,
    String summary,
    String description,
    String status,
    String url,
    Map<String, Object> customFields
) {
    public static TaskIssue empty(String id) {
        return new TaskIssue(id, id, "", "", "", "", Map.of());
    }
}
