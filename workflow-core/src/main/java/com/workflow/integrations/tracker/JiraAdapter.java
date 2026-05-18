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
 * Jira Cloud / Jira Server adapter.
 *
 * <p>Config keys (passed per-call via the {@code config} map):
 * <ul>
 *   <li>{@code baseUrl} or {@code base_url} — e.g. {@code https://mycompany.atlassian.net}
 *   <li>{@code token} — Jira Cloud: personal API token; Jira Server: Bearer token.
 *       For Basic auth supply {@code email} + {@code token} (adapter will base64-encode them).
 *   <li>{@code email} — Jira Cloud email, used together with token for Basic auth.
 *   <li>{@code project} — project key, e.g. {@code PROJ} (used when creating subtasks).
 * </ul>
 */
@Component
public class JiraAdapter implements TaskTracker {

    private static final Logger log = LoggerFactory.getLogger(JiraAdapter.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public JiraAdapter() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper = new ObjectMapper();
        this.mapper.findAndRegisterModules();
    }

    @Override
    public String providerName() { return "jira"; }

    @Override
    @SuppressWarnings("unchecked")
    public TaskIssue fetchIssue(String issueId, Map<String, Object> config) throws Exception {
        HttpRequest req = get(config, "/rest/api/3/issue/" + issueId
            + "?fields=summary,description,status,subtasks,issuetype");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "fetchIssue " + issueId);
        Map<String, Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
        return toIssue(root, base(config));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<TaskIssue> listSubtasks(String parentIssueId, Map<String, Object> config) throws Exception {
        // Subtasks are embedded in the parent issue's fields.subtasks array
        HttpRequest req = get(config, "/rest/api/3/issue/" + parentIssueId + "?fields=subtasks");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "listSubtasks " + parentIssueId);

        Map<String, Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
        List<Map<String, Object>> subtasks = (List<Map<String, Object>>)
            ((Map<String, Object>) root.getOrDefault("fields", Map.of())).getOrDefault("subtasks", List.of());

        List<TaskIssue> result = new ArrayList<>(subtasks.size());
        String baseUrl = base(config);
        for (Map<String, Object> sub : subtasks) {
            result.add(toIssue(sub, baseUrl));
        }
        return result;
    }

    @Override
    public List<String> createSubtasks(String parentIssueId, List<SubtaskSpec> subtasks,
                                        Map<String, Object> config) throws Exception {
        String project = str(config, "project");
        List<String> createdIds = new ArrayList<>(subtasks.size());
        for (SubtaskSpec spec : subtasks) {
            ObjectNode fields = mapper.createObjectNode();
            fields.putObject("project").put("key", project);
            fields.put("summary", spec.summary());
            if (spec.description() != null && !spec.description().isBlank()) {
                fields.set("description", adfDoc(spec.description()));
            }
            fields.putObject("issuetype").put("name", "Subtask");
            if (parentIssueId != null && !parentIssueId.isBlank()) {
                fields.putObject("parent").put("key", parentIssueId);
            }
            ObjectNode body = mapper.createObjectNode();
            body.set("fields", fields);

            HttpRequest req = post(config, "/rest/api/3/issue", body.toString());
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            checkStatus(resp, "createSubtask '" + spec.summary() + "'");

            @SuppressWarnings("unchecked")
            Map<String, Object> created = mapper.readValue(resp.body(), new TypeReference<>() {});
            String id = (String) created.getOrDefault("key", created.get("id"));
            if (id != null) createdIds.add(id);
            log.info("Created Jira subtask: {}", id);
        }
        return createdIds;
    }

    @Override
    public void updateStatus(String issueId, String status, Map<String, Object> config) throws Exception {
        // Step 1: get available transitions
        HttpRequest req = get(config, "/rest/api/3/issue/" + issueId + "/transitions");
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "getTransitions " + issueId);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transitions = (List<Map<String, Object>>)
            root.getOrDefault("transitions", List.of());

        String transitionId = null;
        for (Map<String, Object> t : transitions) {
            String name = str(t, "name");
            if (status.equalsIgnoreCase(name)) {
                transitionId = str(t, "id");
                break;
            }
        }
        if (transitionId == null) {
            throw new IOException("Jira transition '" + status + "' not found for issue " + issueId);
        }

        // Step 2: apply transition
        ObjectNode body = mapper.createObjectNode();
        body.putObject("transition").put("id", transitionId);
        HttpRequest req2 = post(config, "/rest/api/3/issue/" + issueId + "/transitions", body.toString());
        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        if (resp2.statusCode() != 204 && (resp2.statusCode() < 200 || resp2.statusCode() >= 300)) {
            throw new IOException("Failed to transition issue " + issueId + ": HTTP " + resp2.statusCode());
        }
    }

    @Override
    public void addComment(String issueId, String comment, Map<String, Object> config) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.set("body", adfDoc(comment));
        HttpRequest req = post(config, "/rest/api/3/issue/" + issueId + "/comment", body.toString());
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(resp, "addComment " + issueId);
    }

    @Override
    public void updateIssue(String issueId, String summary, String description,
                             Map<String, Object> config) throws Exception {
        ObjectNode fields = mapper.createObjectNode();
        if (summary != null) fields.put("summary", summary);
        if (description != null) fields.set("description", adfDoc(description));
        ObjectNode body = mapper.createObjectNode();
        body.set("fields", fields);

        HttpRequest req = put(config, "/rest/api/3/issue/" + issueId, body.toString());
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 204 && (resp.statusCode() < 200 || resp.statusCode() >= 300)) {
            throw new IOException("Failed to update Jira issue " + issueId + ": HTTP " + resp.statusCode());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private TaskIssue toIssue(Map<String, Object> raw, String baseUrl) {
        String id = str(raw, "id");
        String key = str(raw, "key");
        if (key.isBlank()) key = id;
        Map<String, Object> fields = (Map<String, Object>) raw.getOrDefault("fields", Map.of());
        String summary = str(fields, "summary");
        String description = extractDescription(fields.get("description"));
        String status = "";
        Object statusObj = fields.get("status");
        if (statusObj instanceof Map<?, ?> sm) {
            status = str((Map<String, Object>) sm, "name");
        }
        String url = baseUrl.isBlank() ? "" : baseUrl + "/browse/" + key;
        return new TaskIssue(id, key, summary, description, status, url, Map.of());
    }

    private String extractDescription(Object desc) {
        if (desc == null) return "";
        if (desc instanceof String s) return s;
        // ADF doc — extract plain text from content nodes
        if (desc instanceof Map<?, ?> doc) {
            StringBuilder sb = new StringBuilder();
            extractAdfText(doc, sb);
            return sb.toString().trim();
        }
        return desc.toString();
    }

    @SuppressWarnings("unchecked")
    private void extractAdfText(Object node, StringBuilder sb) {
        if (!(node instanceof Map<?, ?> m)) return;
        Map<String, Object> map = (Map<String, Object>) m;
        Object text = map.get("text");
        if (text instanceof String t) sb.append(t);
        Object content = map.get("content");
        if (content instanceof List<?> list) {
            for (Object child : list) {
                extractAdfText(child, sb);
            }
            sb.append("\n");
        }
    }

    /** Build a minimal Atlassian Document Format (ADF) paragraph node from plain text. */
    private com.fasterxml.jackson.databind.JsonNode adfDoc(String text) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        var content = doc.putArray("content");
        ObjectNode para = content.addObject();
        para.put("type", "paragraph");
        var inlines = para.putArray("content");
        ObjectNode textNode = inlines.addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        return doc;
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
            .header("Authorization", authHeader(config))
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

    private String authHeader(Map<String, Object> config) {
        String email = str(config, "email");
        String token = str(config, "token");
        if (!email.isBlank()) {
            String encoded = Base64.getEncoder().encodeToString((email + ":" + token).getBytes());
            return "Basic " + encoded;
        }
        return "Bearer " + token;
    }

    private String base(Map<String, Object> config) {
        String url = str(config, "baseUrl");
        if (url.isBlank()) url = str(config, "base_url");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : "";
    }

    private void checkStatus(HttpResponse<String> resp, String op) throws IOException {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Jira " + op + " failed: HTTP " + resp.statusCode() + " - " + resp.body());
        }
    }
}
