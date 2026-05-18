package com.workflow.integrations.tracker;

import com.workflow.integrations.YouTrackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class YouTrackAdapter implements TaskTracker {

    private static final Logger log = LoggerFactory.getLogger(YouTrackAdapter.class);

    @Override
    public String providerName() {
        return "youtrack";
    }

    @Override
    public TaskIssue fetchIssue(String issueId, Map<String, Object> config) throws Exception {
        YouTrackClient client = client(config);
        Map<String, Object> raw = client.getIssue(issueId);
        return toIssue(raw, baseUrl(config));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<TaskIssue> listSubtasks(String parentIssueId, Map<String, Object> config) throws Exception {
        YouTrackClient client = client(config);
        List<Map<String, Object>> raw = client.getSubtasks(parentIssueId);
        List<TaskIssue> result = new ArrayList<>(raw.size());
        String url = baseUrl(config);
        for (Map<String, Object> r : raw) result.add(toIssue(r, url));
        return result;
    }

    @Override
    public List<String> createSubtasks(String parentIssueId, List<SubtaskSpec> subtasks,
                                        Map<String, Object> config) throws Exception {
        YouTrackClient client = client(config);
        List<String> createdIds = new ArrayList<>(subtasks.size());
        for (SubtaskSpec spec : subtasks) {
            Map<String, Object> created = client.createIssue(spec.summary(), spec.description(), null, null);
            String id = (String) created.getOrDefault("idReadable", created.get("id"));
            if (id != null) {
                createdIds.add(id);
                if (parentIssueId != null && !parentIssueId.isBlank()) {
                    try {
                        client.linkSubtask(parentIssueId, id);
                    } catch (Exception e) {
                        log.warn("Failed to link issue {} as subtask of {}: {}", id, parentIssueId, e.getMessage());
                    }
                }
            }
        }
        return createdIds;
    }

    @Override
    public void updateStatus(String issueId, String status, Map<String, Object> config) throws Exception {
        client(config).updateState(issueId, status);
    }

    @Override
    public void addComment(String issueId, String comment, Map<String, Object> config) throws Exception {
        YouTrackClient client = client(config);
        client.addComment(issueId, comment);
    }

    @Override
    public void updateIssue(String issueId, String summary, String description,
                             Map<String, Object> config) throws Exception {
        client(config).updateIssue(issueId, summary, description);
    }

    private YouTrackClient client(Map<String, Object> config) {
        String baseUrl = (String) config.getOrDefault("baseUrl", config.getOrDefault("base_url", ""));
        String token = (String) config.getOrDefault("token", "");
        String project = (String) config.getOrDefault("project", "");
        return new YouTrackClient(baseUrl, token, project);
    }

    private String baseUrl(Map<String, Object> config) {
        return (String) config.getOrDefault("baseUrl", config.getOrDefault("base_url", ""));
    }

    @SuppressWarnings("unchecked")
    private TaskIssue toIssue(Map<String, Object> raw, String baseUrl) {
        String id = String.valueOf(raw.getOrDefault("id", ""));
        String readable = String.valueOf(raw.getOrDefault("idReadable", id));
        String summary = String.valueOf(raw.getOrDefault("summary", ""));
        String description = raw.get("description") != null ? raw.get("description").toString() : "";
        String status = extractStatus(raw);
        String url = baseUrl != null && !baseUrl.isBlank() ? baseUrl + "/issue/" + readable : "";
        Map<String, Object> custom = raw.get("customFields") instanceof Map<?, ?> cf
            ? (Map<String, Object>) cf : Map.of();
        return new TaskIssue(id, readable, summary, description, status, url, custom);
    }

    @SuppressWarnings("unchecked")
    private String extractStatus(Map<String, Object> raw) {
        Object custom = raw.get("customFields");
        if (!(custom instanceof List<?> fields)) return "";
        for (Object f : fields) {
            if (!(f instanceof Map<?, ?> field)) continue;
            Object name = ((Map<String, Object>) field).get("name");
            if ("State".equalsIgnoreCase(String.valueOf(name)) || "Status".equalsIgnoreCase(String.valueOf(name))) {
                Object value = ((Map<String, Object>) field).get("value");
                if (value instanceof Map<?, ?> v) {
                    return String.valueOf(((Map<String, Object>) v).getOrDefault("name", ""));
                }
            }
        }
        return "";
    }

    @SuppressWarnings("unused")
    private Map<String, Object> emptyIssue(String id) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("summary", "");
        return m;
    }
}
