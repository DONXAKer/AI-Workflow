package com.workflow.integrations.tracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * GitLab Issues adapter.
 *
 * <p>GitLab supports parent–child tasks via the {@code parent_id} field (GitLab 15.6+, free tier).
 * {@link #createSubtasks} creates child issues under the parent. {@link #listSubtasks} fetches
 * issues where {@code parent_id} equals the given parent issue IID.
 *
 * <p>Config keys:
 * <ul>
 *   <li>{@code token} — GitLab personal access token or project token.
 *   <li>{@code baseUrl} / {@code base_url} — GitLab instance URL, e.g. {@code https://gitlab.com}.
 *   <li>{@code projectId} / {@code project_id} — numeric project ID.
 * </ul>
 */
@Component
public class GitLabIssuesAdapter implements TaskTracker {

    private static final Logger log = LoggerFactory.getLogger(GitLabIssuesAdapter.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public GitLabIssuesAdapter() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper = new ObjectMapper();
        this.mapper.findAndRegisterModules();
    }

    @Override
    public String providerName() { return "gitlab"; }

    @Override
    @SuppressWarnings("unchecked")
    public TaskIssue fetchIssue(String issueId, Map<String, Object> config) throws Exception {
        HttpRequest req = get(config, projectPath(config, "/issues/" + issueId));
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "fetchIssue " + issueId);
        Map<String, Object> raw = mapper.readValue(resp.body(), new TypeReference<>() {});
        return toIssue(raw, base(config));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<TaskIssue> listSubtasks(String parentIssueId, Map<String, Object> config) throws Exception {
        String path = projectPath(config, "/issues?parent_id=" + parentIssueId + "&per_page=100");
        HttpRequest req = get(config, path);
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "listSubtasks of " + parentIssueId);

        List<Map<String, Object>> raw = mapper.readValue(resp.body(), new TypeReference<>() {});
        List<TaskIssue> result = new ArrayList<>(raw.size());
        String baseUrl = base(config);
        for (Map<String, Object> item : raw) result.add(toIssue(item, baseUrl));
        return result;
    }

    @Override
    public List<String> createSubtasks(String parentIssueId, List<SubtaskSpec> subtasks,
                                        Map<String, Object> config) throws Exception {
        List<String> createdIds = new ArrayList<>(subtasks.size());
        for (SubtaskSpec spec : subtasks) {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("title", spec.summary());
            if (spec.description() != null) payload.put("description", spec.description());
            if (parentIssueId != null && !parentIssueId.isBlank()) {
                try {
                    payload.put("parent_id", Long.parseLong(parentIssueId));
                } catch (NumberFormatException e) {
                    log.warn("parent_id must be numeric for GitLab, got '{}' — skipping parent link", parentIssueId);
                }
            }

            HttpRequest req = post(config, projectPath(config, "/issues"), payload.toString());
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            checkStatus(resp, "createSubtask '" + spec.summary() + "'");

            @SuppressWarnings("unchecked")
            Map<String, Object> created = mapper.readValue(resp.body(), new TypeReference<>() {});
            Object iid = created.get("iid");
            String id = iid != null ? String.valueOf(iid) : str(created, "id");
            if (!id.isBlank()) {
                createdIds.add(id);
                log.info("Created GitLab issue: #{}", id);
            }
        }
        return createdIds;
    }

    @Override
    public void updateStatus(String issueId, String status, Map<String, Object> config) throws Exception {
        String glState = "closed".equalsIgnoreCase(status) || "done".equalsIgnoreCase(status) ? "close" : "reopen";
        ObjectNode payload = mapper.createObjectNode();
        payload.put("state_event", glState);
        HttpRequest req = put(config, projectPath(config, "/issues/" + issueId), payload.toString());
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "updateStatus #" + issueId);
    }

    @Override
    public void addComment(String issueId, String comment, Map<String, Object> config) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("body", comment);
        HttpRequest req = post(config, projectPath(config, "/issues/" + issueId + "/notes"), payload.toString());
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "addComment #" + issueId);
    }

    @Override
    public void updateIssue(String issueId, String summary, String description,
                             Map<String, Object> config) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        if (summary != null) payload.put("title", summary);
        if (description != null) payload.put("description", description);
        HttpRequest req = put(config, projectPath(config, "/issues/" + issueId), payload.toString());
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "updateIssue #" + issueId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private TaskIssue toIssue(Map<String, Object> raw, String baseUrl) {
        Object iid = raw.get("iid");
        String id = iid != null ? String.valueOf(iid) : str(raw, "id");
        String title = str(raw, "title");
        String description = str(raw, "description");
        String state = str(raw, "state");
        String url = str(raw, "web_url");
        if (url.isBlank() && !baseUrl.isBlank()) {
            url = baseUrl + "/-/issues/" + id;
        }
        return new TaskIssue(id, "#" + id, title, description, state, url, Map.of());
    }

    private String projectPath(Map<String, Object> config, String suffix) {
        String projectId = str(config, "projectId");
        if (projectId.isBlank()) projectId = str(config, "project_id");
        String encoded = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        return "/api/v4/projects/" + encoded + suffix;
    }

    private HttpRequest get(Map<String, Object> config, String path) {
        return request(config, "GET", path, null);
    }

    private HttpRequest post(Map<String, Object> config, String path, String body) {
        return request(config, "POST", path, body);
    }

    private HttpRequest put(Map<String, Object> config, String path, String body) {
        return request(config, "PUT", path, body);
    }

    private HttpRequest request(Map<String, Object> config, String method, String path, String body) {
        String baseUrl = base(config);
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("PRIVATE-TOKEN", str(config, "token"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60));
        switch (method) {
            case "GET" -> b.GET();
            case "POST" -> b.POST(body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
            case "PUT" -> b.PUT(body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
            default -> b.method(method, body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
        }
        return b.build();
    }

    private String base(Map<String, Object> config) {
        String url = str(config, "baseUrl");
        if (url.isBlank()) url = str(config, "base_url");
        if (url.isBlank()) url = "https://gitlab.com";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : (v != null ? String.valueOf(v) : "");
    }

    private void checkStatus(HttpResponse<String> resp, String op) throws IOException {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("GitLab Issues " + op + " failed: HTTP " + resp.statusCode() + " - " + resp.body());
        }
    }
}
