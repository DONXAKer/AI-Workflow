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

public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final String token;
    private final String owner;
    private final String repo;
    private final String apiBase;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitHubClient(String token, String owner, String repo, String url) {
        this.token = token;
        this.owner = owner;
        this.repo = repo;
        this.apiBase = (url != null && !url.isBlank()) ? url : "https://api.github.com";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    public String getBranchSha(String branch) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("GET", repoPath("/git/refs/heads/" + branch), null);
        HttpResponse<String> response = sendRaw(request);

        if (response.statusCode() == 404) {
            throw new IOException("Branch not found: " + branch);
        }
        checkResponse(response);

        JsonNode json = objectMapper.readTree(response.body());
        // Can be an array or single object
        if (json.isArray()) {
            if (json.size() == 0) throw new IOException("Branch ref not found: " + branch);
            return json.get(0).path("object").path("sha").asText();
        }
        return json.path("object").path("sha").asText();
    }

    public Map<String, Object> createBranch(String branchName, String baseBranch) throws IOException, InterruptedException {
        String sha = getBranchSha(baseBranch);

        Map<String, Object> body = new HashMap<>();
        body.put("ref", "refs/heads/" + branchName);
        body.put("sha", sha);

        HttpRequest request = buildRequest("POST", repoPath("/git/refs"), toJson(body));
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public List<String> findBranchesForIssue(String issueId) throws IOException, InterruptedException {
        List<String> matching = new ArrayList<>();
        int page = 1;

        while (true) {
            HttpRequest request = buildRequest("GET",
                repoPath("/branches?per_page=100&page=" + page), null);
            HttpResponse<String> response = send(request);

            List<Map<String, Object>> branches = parseList(response);
            if (branches.isEmpty()) break;

            for (Map<String, Object> branch : branches) {
                String name = (String) branch.get("name");
                if (name != null && name.contains(issueId)) {
                    matching.add(name);
                }
            }

            if (branches.size() < 100) break;
            page++;
        }

        return matching;
    }

    public String getFileSha(String path, String branch) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("GET",
            repoPath("/contents/" + encoded + "?ref=" + branch), null);
        HttpResponse<String> response = sendRaw(request);

        if (response.statusCode() == 404) return null;
        checkResponse(response);

        JsonNode json = objectMapper.readTree(response.body());
        return json.path("sha").asText(null);
    }

    public Map<String, Object> createOrUpdateFile(String branch, String filePath, String content,
                                                   String commitMessage) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = new HashMap<>();
        body.put("message", commitMessage);
        body.put("content", encodedContent);
        body.put("branch", branch);

        // Check if file exists to get SHA for update
        String existingSha = getFileSha(filePath, branch);
        if (existingSha != null) {
            body.put("sha", existingSha);
        }

        HttpRequest request = buildRequest("PUT", repoPath("/contents/" + encoded), toJson(body));
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public void deleteFile(String branch, String filePath,
                           String commitMessage) throws IOException, InterruptedException {
        String sha = getFileSha(filePath, branch);
        if (sha == null) {
            log.warn("File not found for deletion: {}", filePath);
            return;
        }

        String encoded = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
        Map<String, Object> body = new HashMap<>();
        body.put("message", commitMessage);
        body.put("sha", sha);
        body.put("branch", branch);

        HttpRequest request = buildRequest("DELETE", repoPath("/contents/" + encoded), toJson(body));
        HttpResponse<String> response = sendRaw(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to delete file: HTTP " + response.statusCode());
        }
    }

    public Map<String, Object> createPullRequest(String title, String body, String head, String base,
                                                  List<String> labels) throws IOException, InterruptedException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("title", title);
        requestBody.put("body", body != null ? body : "");
        requestBody.put("head", head);
        requestBody.put("base", base != null ? base : "main");

        HttpRequest request = buildRequest("POST", repoPath("/pulls"), toJson(requestBody));
        HttpResponse<String> response = send(request);
        Map<String, Object> pr = parseMap(response);

        // Add labels if specified
        if (labels != null && !labels.isEmpty()) {
            Number prNumber = (Number) pr.get("number");
            if (prNumber != null) {
                Map<String, Object> labelBody = new HashMap<>();
                labelBody.put("labels", labels);
                HttpRequest labelRequest = buildRequest("POST",
                    repoPath("/issues/" + prNumber.intValue() + "/labels"), toJson(labelBody));
                sendRaw(labelRequest); // Ignore label errors
            }
        }

        return pr;
    }

    public Map<String, Object> getPullRequest(int prNumber) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("GET", repoPath("/pulls/" + prNumber), null);
        HttpResponse<String> response = send(request);
        return parseMap(response);
    }

    public Map<String, Object> getPullRequestByBranch(String branch) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(branch, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("GET",
            repoPath("/pulls?head=" + owner + ":" + encoded + "&state=open"), null);
        HttpResponse<String> response = send(request);

        List<Map<String, Object>> prs = parseList(response);
        if (prs.isEmpty()) return null;
        return prs.get(0);
    }

    public List<Map<String, Object>> compareBranches(String base, String head) throws IOException, InterruptedException {
        String encodedBase = URLEncoder.encode(base, StandardCharsets.UTF_8);
        String encodedHead = URLEncoder.encode(head, StandardCharsets.UTF_8);
        HttpRequest request = buildRequest("GET",
            repoPath("/compare/" + encodedBase + "..." + encodedHead), null);
        HttpResponse<String> response = send(request);

        Map<String, Object> result = parseMap(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.getOrDefault("files", new ArrayList<>());
        return files;
    }

    public void triggerWorkflow(String workflowFile, String ref,
                                Map<String, Object> inputs) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("ref", ref);
        if (inputs != null && !inputs.isEmpty()) {
            body.put("inputs", inputs);
        }

        HttpRequest request = buildRequest("POST",
            repoPath("/actions/workflows/" + workflowFile + "/dispatches"), toJson(body));
        HttpResponse<String> response = sendRaw(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to trigger workflow: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    public List<Map<String, Object>> getWorkflowRuns(String branch, String workflowFile,
                                                      String createdAfter) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(repoPath("/actions/runs?per_page=20"));
        if (branch != null) url.append("&branch=").append(URLEncoder.encode(branch, StandardCharsets.UTF_8));
        if (workflowFile != null) url.append("&workflow_id=").append(workflowFile);
        if (createdAfter != null) url.append("&created=%3E").append(createdAfter);

        HttpRequest request = buildRequest("GET", url.toString(), null);
        HttpResponse<String> response = send(request);

        Map<String, Object> result = parseMap(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runs = (List<Map<String, Object>>) result.getOrDefault("workflow_runs", new ArrayList<>());
        return runs;
    }

    public List<Map<String, Object>> getRunJobs(long runId) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("GET", repoPath("/actions/runs/" + runId + "/jobs"), null);
        HttpResponse<String> response = send(request);

        Map<String, Object> result = parseMap(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) result.getOrDefault("jobs", new ArrayList<>());
        return jobs;
    }

    public List<Map<String, Object>> waitForWorkflowRuns(String branch, int timeoutSeconds,
                                                          List<String> workflowFiles,
                                                          String triggeredAfter) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        int pollInterval = 10;

        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> allRuns = new ArrayList<>();

            for (String workflowFile : workflowFiles) {
                List<Map<String, Object>> runs = getWorkflowRuns(branch, workflowFile, triggeredAfter);
                allRuns.addAll(runs);
            }

            if (!allRuns.isEmpty()) {
                // Check if all runs are complete
                boolean allComplete = allRuns.stream()
                    .allMatch(run -> {
                        String status = (String) run.get("status");
                        return "completed".equals(status);
                    });

                if (allComplete) {
                    return allRuns;
                }
            }

            Thread.sleep(pollInterval * 1000L);
            pollInterval = Math.min(pollInterval + 5, 30);
        }

        return Collections.emptyList();
    }

    private String repoPath(String path) {
        return apiBase + "/repos/" + owner + "/" + repo + path;
    }

    private HttpRequest buildRequest(String method, String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", "application/json")
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

    private HttpResponse<String> sendRaw(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = sendRaw(request);
        checkResponse(response);
        return response;
    }

    private void checkResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub API error: HTTP " + response.statusCode() +
                " for " + response.request().uri() + " - " + response.body());
        }
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
