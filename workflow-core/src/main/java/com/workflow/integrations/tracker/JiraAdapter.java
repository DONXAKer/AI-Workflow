package com.workflow.integrations.tracker;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Placeholder Jira adapter. Wiring the REST v3 endpoints is tracked under Срез 3 follow-up;
 * the bean exists now so YAML can reference {@code provider: jira} without failing startup.
 * Every method throws {@link UnsupportedOperationException} until implemented.
 */
@Component
public class JiraAdapter implements TaskTracker {

    @Override public String providerName() { return "jira"; }

    @Override
    public TaskIssue fetchIssue(String issueId, Map<String, Object> config) {
        throw new UnsupportedOperationException("Jira adapter not yet implemented");
    }

    @Override
    public List<TaskIssue> listSubtasks(String parentIssueId, Map<String, Object> config) {
        throw new UnsupportedOperationException("Jira adapter not yet implemented");
    }

    @Override
    public List<String> createSubtasks(String parentIssueId, List<SubtaskSpec> subtasks, Map<String, Object> config) {
        throw new UnsupportedOperationException("Jira adapter not yet implemented");
    }

    @Override
    public void updateStatus(String issueId, String status, Map<String, Object> config) {
        throw new UnsupportedOperationException("Jira adapter not yet implemented");
    }

    @Override
    public void addComment(String issueId, String comment, Map<String, Object> config) {
        throw new UnsupportedOperationException("Jira adapter not yet implemented");
    }
}
