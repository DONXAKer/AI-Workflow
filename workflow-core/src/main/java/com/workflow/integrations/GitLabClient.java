package com.workflow.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.*;

public class GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabClient.class);

    private final String baseUrl;
    private final String token;
    private final int projectId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final String apiBase;

    public GitLabClient(String url, String token, int projectId) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.token = token;
        this.projectId = projectId;
        this.apiBase = this.baseUrl + "/api/v4";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    public Map<String, Object> createBranch(String branchName, String ref) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("branch", branchName);
        body.put("ref", ref);

        HttpRequest request = buildRequest("POST", projectPath("/repository/branches"), toJson(body));
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public Map<String, Object> createFile(String branch, String filePath, String content,
                                          String commitMessage) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("branch", branch);
        body.put("content", content);
        body.put("commit_message", commitMessage);

        String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("POST", projectPath("/repository/files/" + encodedPath), toJson(body));
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public Map<String, Object> updateFile(String branch, String filePath, String content,
                                          String commitMessage) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("branch", branch);
        body.put("content", content);
        body.put("commit_message", commitMessage);

        String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("PUT", projectPath("/repository/files/" + encodedPath), toJson(body));
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public void deleteFile(String branch, String filePath,
                           String commitMessage) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("branch", branch);
        body.put("commit_message", commitMessage);

        String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("DELETE", projectPath("/repository/files/" + encodedPath), toJson(body));
        HttpResponse<String> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to delete file: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    public Map<String, Object> createMergeRequest(String sourceBranch, String title, String description,
                                                   List<String> labels,
                                                   String targetBranch) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("source_branch", sourceBranch);
        body.put("target_branch", targetBranch != null ? targetBranch : "main");
        body.put("title", title);
        if (description != null) body.put("description", description);
        if (labels != null && !labels.isEmpty()) body.put("labels", String.join(",", labels));

        HttpRequest request = buildRequest("POST", projectPath("/merge_requests"), toJson(body));
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public List<Map<String, Object>> getMrPipelines(int mrIid) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("GET", projectPath("/merge_requests/" + mrIid + "/pipelines"), null);
        HttpResponse<String> response = send(request);
        return parseList(response);
    }

    public Map<String, Object> getPipelineStatus(int pipelineId) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("GET", projectPath("/pipelines/" + pipelineId), null);
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public List<String> findBranchesForIssue(String issueId) throws IOException, InterruptedException {
        List<String> matching = new ArrayList<>();
        int page = 1;

        while (true) {
            HttpRequest request = buildRequest("GET",
                projectPath("/repository/branches?per_page=100&page=" + page), null);
            HttpResponse<String> response = send(request);

            List<Map<String, Object>> branches = parseList(response);
            if (branches.isEmpty()) break;

            for (Map<String, Object> branch : branches) {
                String name = (String) branch.get("name");
                if (name != null && name.contains(issueId)) {
                    matching.add(name);
                }
            }

            String nextPage = response.headers().firstValue("X-Next-Page").orElse("");
            if (nextPage.isBlank()) break;
            page++;
        }

        return matching;
    }

    public Map<String, Object> getMergeRequest(int mrIid) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("GET", projectPath("/merge_requests/" + mrIid), null);
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public Map<String, Object> getMergeRequestByBranch(String sourceBranch) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(sourceBranch, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("GET",
            projectPath("/merge_requests?source_branch=" + encoded + "&state=opened"), null);
        HttpResponse<String> response = send(request);

        List<Map<String, Object>> mrs = parseList(response);
        if (mrs.isEmpty()) return null;
        return mrs.get(0);
    }

    public List<Map<String, Object>> compareBranches(String fromRef,
                                                      String toRef) throws IOException, InterruptedException {
        String from = URLEncoder.encode(fromRef, StandardCharsets.UTF_8);
        String to = URLEncoder.encode(toRef, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("GET",
            projectPath("/repository/compare?from=" + from + "&to=" + to), null);
        HttpResponse<String> response = send(request);

        Map<String, Object> result = parseMap(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) result.getOrDefault("diffs", new ArrayList<>());
        return diffs;
    }

    public String waitForPipeline(int pipelineId, int timeoutSeconds) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        int pollInterval = 5;

        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> status = getPipelineStatus(pipelineId);
            String pipelineStatus = (String) status.get("status");

            log.debug("Pipeline {} status: {}", pipelineId, pipelineStatus);

            if (pipelineStatus != null) {
                switch (pipelineStatus) {
                    case "success", "failed", "canceled", "skipped" -> {
                        return pipelineStatus;
                    }
                }
            }

            Thread.sleep(pollInterval * 1000L);
            pollInterval = Math.min(pollInterval * 2, 30);
        }

        return "timeout";
    }

    private String projectPath(String path) {
        return apiBase + "/projects/" + projectId + path;
    }

    private HttpRequest buildRequest(String method, String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60));

        switch (method) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
            case "PUT" -> builder.PUT(body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
            case "DELETE" -> {
                if (body != null) {
                    builder.method("DELETE", HttpRequest.BodyPublishers.ofString(body));
                } else {
                    builder.DELETE();
                }
            }
            default -> builder.method(method, body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitLab API error: HTTP " + response.statusCode() +
                " for " + request.uri() + " - " + response.body());
        }
        return response;
    }

    private Map<String, Object> parseMap(HttpResponse<String> response) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        return result;
    }

    private List<Map<String, Object>> parseList(HttpResponse<String> response) throws IOException {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = objectMapper.readValue(response.body(),
            new TypeReference<List<Map<String, Object>>>() {});
        return result;
    }

    private String toJson(Object obj) throws IOException {
        return objectMapper.writeValueAsString(obj);
    }
}
