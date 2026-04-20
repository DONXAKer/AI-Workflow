package com.workflow.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YouTrackClient {

    private static final Logger log = LoggerFactory.getLogger(YouTrackClient.class);

    private final String baseUrl;
    private final String token;
    private final String project;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String cachedProjectId;

    public YouTrackClient(String baseUrl, String token, String project) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.project = project;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    public Map<String, Object> createIssue(String summary, String description,
                                            String issueType, String priority) throws IOException, InterruptedException {
        String projectId = getProjectId();

        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("project").put("id", projectId);
        body.put("summary", summary);
        if (description != null) {
            body.put("description", description);
        }

        String json = body.toString();
        HttpRequest request = buildRequest("POST", "/api/issues?fields=id,idReadable,summary", json);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to create YouTrack issue: HTTP " + response.statusCode() + " - " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

        String issueId = (String) result.get("id");
        if (issueId != null && (issueType != null || priority != null)) {
            setCustomFields(issueId, issueType, priority);
        }

        return result;
    }

    public Map<String, Object> getIssue(String issueId) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("GET",
            "/api/issues/" + issueId + "?fields=id,idReadable,summary,description,customFields(name,value(name,text))",
            null);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            throw new IOException("Issue not found: " + issueId);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to get YouTrack issue: HTTP " + response.statusCode());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        return result;
    }

    public List<Map<String, Object>> getSubtasks(String parentIssueId) throws IOException, InterruptedException {
        String query = URLEncoder.encode("subtask of: " + parentIssueId, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("GET",
            "/api/issues?query=" + query + "&fields=id,idReadable,summary",
            null);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to get subtasks: HTTP " + response.statusCode());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = objectMapper.readValue(response.body(),
            new TypeReference<List<Map<String, Object>>>() {});
        return result;
    }

    public Map<String, Object> updateIssue(String issueId, String summary,
                                            String description) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        if (summary != null) {
            body.put("summary", summary);
        }
        if (description != null) {
            body.put("description", description);
        }

        HttpRequest request = buildRequest("POST",
            "/api/issues/" + issueId + "?fields=id,idReadable,summary",
            body.toString());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to update YouTrack issue: HTTP " + response.statusCode());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        return result;
    }

    public Map<String, Object> addComment(String issueId, String text) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);

        HttpRequest request = buildRequest("POST",
            "/api/issues/" + issueId + "/comments?fields=id,text",
            body.toString());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to add comment: HTTP " + response.statusCode());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        return result;
    }

    private String getProjectId() throws IOException, InterruptedException {
        if (cachedProjectId != null) {
            return cachedProjectId;
        }

        String query = URLEncoder.encode(project, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("GET",
            "/api/admin/projects?fields=id,shortName&query=" + query,
            null);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to get YouTrack project: HTTP " + response.statusCode());
        }

        JsonNode projects = objectMapper.readTree(response.body());
        if (projects.isArray() && projects.size() > 0) {
            cachedProjectId = projects.get(0).path("id").asText();
            return cachedProjectId;
        }

        throw new IOException("YouTrack project not found: " + project);
    }

    private void setCustomFields(String issueId, String issueType,
                                  String priority) throws IOException, InterruptedException {
        List<Map<String, Object>> fields = new ArrayList<>();

        if (issueType != null) {
            Map<String, Object> typeField = new HashMap<>();
            typeField.put("$type", "SingleEnumIssueCustomField");
            typeField.put("name", "Type");
            Map<String, Object> typeValue = new HashMap<>();
            typeValue.put("$type", "EnumBundleElement");
            typeValue.put("name", issueType);
            typeField.put("value", typeValue);
            fields.add(typeField);
        }

        if (priority != null) {
            Map<String, Object> priorityField = new HashMap<>();
            priorityField.put("$type", "SingleEnumIssueCustomField");
            priorityField.put("name", "Priority");
            Map<String, Object> priorityValue = new HashMap<>();
            priorityValue.put("$type", "EnumBundleElement");
            priorityValue.put("name", priority);
            priorityField.put("value", priorityValue);
            fields.add(priorityField);
        }

        if (fields.isEmpty()) return;

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode customFields = body.putArray("customFields");
        for (Map<String, Object> field : fields) {
            customFields.addPOJO(field);
        }

        HttpRequest request = buildRequest("POST",
            "/api/issues/" + issueId + "?fields=id",
            body.toString());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Failed to set custom fields on issue {}: HTTP {}", issueId, response.statusCode());
        }
    }

    private HttpRequest buildRequest(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60));

        if ("GET".equals(method)) {
            builder.GET();
        } else if ("POST".equals(method)) {
            builder.POST(body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.method(method, body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }
}
