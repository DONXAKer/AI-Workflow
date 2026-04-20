package com.workflow.integrations.tracker;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic task tracker contract. Each supported tracker (YouTrack, Jira, Linear)
 * ships an adapter that registers itself under a unique {@link #providerName()}.
 *
 * <p>Adapters receive their credentials via the provider-specific {@code config} map rather
 * than constructor injection, so a single Spring-managed bean can serve runs for different
 * integration instances (e.g. two YouTrack projects).
 */
public interface TaskTracker {

    /** Unique key matched against {@code integrations.tracker.provider} in pipeline YAML. */
    String providerName();

    /** Load a single issue by ID. */
    TaskIssue fetchIssue(String issueId, Map<String, Object> config) throws Exception;

    /** List direct subtasks of a parent issue. */
    List<TaskIssue> listSubtasks(String parentIssueId, Map<String, Object> config) throws Exception;

    /** Create N subtasks under {@code parentIssueId}. Returns the IDs of created issues. */
    List<String> createSubtasks(String parentIssueId, List<SubtaskSpec> subtasks,
                                 Map<String, Object> config) throws Exception;

    /** Transition an issue's state. Status mapping is provider-specific. */
    void updateStatus(String issueId, String status, Map<String, Object> config) throws Exception;

    /** Append a comment to an issue. */
    void addComment(String issueId, String comment, Map<String, Object> config) throws Exception;
}
