package com.workflow.integrations.tracker;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Placeholder Linear adapter. See {@link JiraAdapter} for the same rationale.
 */
@Component
public class LinearAdapter implements TaskTracker {

    @Override public String providerName() { return "linear"; }

    @Override
    public TaskIssue fetchIssue(String issueId, Map<String, Object> config) {
        throw new UnsupportedOperationException("Linear adapter not yet implemented");
    }

    @Override
    public List<TaskIssue> listSubtasks(String parentIssueId, Map<String, Object> config) {
        throw new UnsupportedOperationException("Linear adapter not yet implemented");
    }

    @Override
    public List<String> createSubtasks(String parentIssueId, List<SubtaskSpec> subtasks, Map<String, Object> config) {
        throw new UnsupportedOperationException("Linear adapter not yet implemented");
    }

    @Override
    public void updateStatus(String issueId, String status, Map<String, Object> config) {
        throw new UnsupportedOperationException("Linear adapter not yet implemented");
    }

    @Override
    public void addComment(String issueId, String comment, Map<String, Object> config) {
        throw new UnsupportedOperationException("Linear adapter not yet implemented");
    }
}
