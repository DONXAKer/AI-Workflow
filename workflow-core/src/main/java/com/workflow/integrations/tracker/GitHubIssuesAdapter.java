package com.workflow.integrations.tracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * GitHub Issues adapter.
 *
 * <p>GitHub has no native subtask hierarchy, so {@link #createSubtasks} creates individual issues
 * and posts a summary comment on the parent issue with links. {@link #listSubtasks} searches
 * open issues whose body contains a back-reference to the parent issue number.
 *
 * <p>Config keys:
 * <ul>
 *   <li>{@code token} — GitHub personal access token or fine-grained PAT.
 *   <li>{@code owner} — repository owner (user or org).
 *   <li>{@code repo}  — repository name.
 *   <li>{@code baseUrl} / {@code base_url} — optional; defaults to {@code https://api.github.com}
 *       (override for GitHub Enterprise).
 * </ul>
 */
@Component
public class GitHubIssuesAdapter implements TaskTracker {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssuesAdapter.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public GitHubIssuesAdapter() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper = new ObjectMapper();
        this.mapper.findAndRegisterModules();
    }

    @Override
    public String providerName() { return "github"; }

    @Override
    @SuppressWarnings("unchecked")
    public TaskIssue fetchIssue(String issueId, Map<String, Object> config) throws Exception {
        HttpRequest req = get(config, repoPath(config, "/issues/" + issueId));
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "fetchIssue #" + issueId);
        Map<String, Object> raw = mapper.readValue(resp.body(), new TypeReference<>() {});
        return toIssue(raw);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<TaskIssue> listSubtasks(String parentIssueId, Map<String, Object> config) throws Exception {
        // Search issues that reference the parent in their body
        String query = "repo:" + str(config, "owner") + "/" + str(config, "repo")
            + " is:issue in:body \"parent: #" + parentIssueId + "\"";
        String encoded = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest req = get(config, "/search/issues?q=" + encoded + "&per_page=100");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "listSubtasks of #" + parentIssueId);

        Map<String, Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
        List<Map<String, Object>> items = (List<Map<String, Object>>)
            root.getOrDefault("items", List.of());

        List<TaskIssue> result = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) result.add(toIssue(item));
        return result;
    }

    @Override
    public List<String> createSubtasks(String parentIssueId, List<SubtaskSpec> subtasks,
                                        Map<String, Object> config) throws Exception {
        List<String> createdIds = new ArrayList<>(subtasks.size());
        List<String> links = new ArrayList<>();

        for (SubtaskSpec spec : subtasks) {
            String body = spec.description() != null ? spec.description() : "";
            if (parentIssueId != null && !parentIssueId.isBlank()) {
                body = body + "\n\n---\nparent: #" + parentIssueId;
            }

            ObjectNode payload = mapper.createObjectNode();
            payload.put("title", spec.summary());
            payload.put("body", body);

            HttpRequest req = post(config, repoPath(config, "/issues"), payload.toString());
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            checkStatus(resp, "createSubtask '" + spec.summary() + "'");

            @SuppressWarnings("unchecked")
            Map<String, Object> created = mapper.readValue(resp.body(), new TypeReference<>() {});
            Object number = created.get("number");
            String id = number != null ? String.valueOf(number) : "";
            String htmlUrl = str(created, "html_url");
            if (!id.isBlank()) {
                createdIds.add(id);
                links.add("- #" + id + " " + spec.summary() + " " + htmlUrl);
                log.info("Created GitHub issue: #{}", id);
            }
        }

        // Post summary comment on parent issue
        if (parentIssueId != null && !parentIssueId.isBlank() && !links.isEmpty()) {
            try {
                String comment = "Subtasks created by AI-Workflow pipeline:\n" + String.join("\n", links);
                addComment(parentIssueId, comment, config);
            } catch (Exception e) {
                log.warn("Failed to post subtask summary on issue #{}: {}", parentIssueId, e.getMessage());
            }
        }
        return createdIds;
    }

    @Override
    public void updateStatus(String issueId, String status, Map<String, Object> config) throws Exception {
        // GitHub "status" = issue state: open or closed
        String ghState = "closed".equalsIgnoreCase(status) || "done".equalsIgnoreCase(status) ? "closed" : "open";
        ObjectNode payload = mapper.createObjectNode();
        payload.put("state", ghState);
        HttpRequest req = patch(config, repoPath(config, "/issues/" + issueId), payload.toString());
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "updateStatus #" + issueId);
    }

    @Override
    public void addComment(String issueId, String comment, Map<String, Object> config) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("body", comment);
        HttpRequest req = post(config, repoPath(config, "/issues/" + issueId + "/comments"), payload.toString());
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "addComment #" + issueId);
    }

    @Override
    public void updateIssue(String issueId, String summary, String description,
                             Map<String, Object> config) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        if (summary != null) payload.put("title", summary);
        if (description != null) payload.put("body", description);
        HttpRequest req = patch(config, repoPath(config, "/issues/" + issueId), payload.toString());
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "updateIssue #" + issueId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private TaskIssue toIssue(Map<String, Object> raw) {
        Object number = raw.get("number");
        String id = number != null ? String.valueOf(number) : str(raw, "id");
        String title = str(raw, "title");
        String body = str(raw, "body");
        Object stateObj = raw.get("state");
        String state = stateObj instanceof String s ? s : "";
        String url = str(raw, "html_url");
        return new TaskIssue(id, "#" + id, title, body, state, url, Map.of());
    }

    private String repoPath(Map<String, Object> config, String suffix) {
        String owner = str(config, "owner");
        String repo = str(config, "repo");
        return "/repos/" + owner + "/" + repo + suffix;
    }

    private HttpRequest get(Map<String, Object> config, String path) {
        return request(config, "GET", path, null);
    }

    private HttpRequest post(Map<String, Object> config, String path, String body) {
        return request(config, "POST", path, body);
    }

    private HttpRequest patch(Map<String, Object> config, String path, String body) {
        return request(config, "PATCH", path, body);
    }

    private HttpRequest request(Map<String, Object> config, String method, String path, String body) {
        String apiBase = base(config);
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(apiBase + path))
            .header("Authorization", "Bearer " + str(config, "token"))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60));
        switch (method) {
            case "GET" -> b.GET();
            case "POST" -> b.POST(body != null
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
        if (url.isBlank()) url = "https://api.github.com";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : "";
    }

    private void checkStatus(HttpResponse<String> resp, String op) throws IOException {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("GitHub Issues " + op + " failed: HTTP " + resp.statusCode() + " - " + resp.body());
        }
    }
}
