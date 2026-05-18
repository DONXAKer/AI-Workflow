package com.workflow.integrations.vcs;

import com.workflow.integrations.GitLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GitLabProvider implements VcsProvider {

    private static final Logger log = LoggerFactory.getLogger(GitLabProvider.class);

    @Override public String providerName() { return "gitlab"; }

    @Override
    public void createBranch(String branchName, String baseBranch, Map<String, Object> config) throws Exception {
        client(config).createBranch(branchName, baseBranch);
    }

    @Override
    public void applyChanges(String branch, List<VcsChange> changes, String commitMessage,
                              Map<String, Object> config) throws Exception {
        GitLabClient client = client(config);
        for (VcsChange change : changes) {
            switch (change.operation()) {
                case CREATE -> client.createFile(branch, change.path(), change.content(), commitMessage);
                case UPDATE -> client.updateFile(branch, change.path(), change.content(), commitMessage);
                case DELETE -> client.deleteFile(branch, change.path(), commitMessage);
            }
        }
    }

    @Override
    public MergeRequestRef openMergeRequest(String sourceBranch, String targetBranch, String title,
                                             String description, List<String> labels,
                                             Map<String, Object> config) throws Exception {
        Map<String, Object> mr = client(config).createMergeRequest(sourceBranch, title, description, labels, targetBranch);
        long iid = toLong(mr.get("iid"));
        String url = (String) mr.getOrDefault("web_url", "");
        return new MergeRequestRef("gitlab", iid, title, sourceBranch, targetBranch, url, "open");
    }

    @Override
    public CiPipelineStatus waitForCi(long mrId, int timeoutSeconds, Map<String, Object> config) throws Exception {
        GitLabClient client = client(config);
        List<Map<String, Object>> pipelines = client.getMrPipelines((int) mrId);
        if (pipelines.isEmpty()) return new CiPipelineStatus(0, "", "not_found", Map.of());
        long pipelineId = toLong(pipelines.get(0).get("id"));
        String status = client.waitForPipeline((int) pipelineId, timeoutSeconds);
        Map<String, Object> details = client.getPipelineStatus((int) pipelineId);
        return new CiPipelineStatus(pipelineId,
            String.valueOf(details.getOrDefault("web_url", "")), status, details);
    }

    @Override
    public MergeResult merge(long mrId, MergeStrategy strategy, boolean deleteBranchAfter,
                              Map<String, Object> config) throws Exception {
        // GitLab's API exposes squash vs. plain merge as a boolean. REBASE has no
        // first-class API equivalent — the recommended pattern is rebase-then-merge via the
        // separate /rebase endpoint, which is out of scope here. We log + fall back to merge.
        boolean squash = strategy == MergeStrategy.SQUASH;
        if (strategy == MergeStrategy.REBASE) {
            log.warn("GitLab REBASE strategy not directly supported by /merge endpoint; falling back to MERGE for mr={}", mrId);
        }
        Map<String, Object> response = client(config).mergeMr((int) mrId, squash, deleteBranchAfter, null);

        // Error envelope from GitLabClient.mergeMr() — surface as conflict when 405 (the
        // typical "cannot be merged" status), otherwise propagate as an unrecoverable failure.
        if (Boolean.TRUE.equals(response.get("error"))) {
            int httpStatus = response.get("http_status") instanceof Number n ? n.intValue() : 0;
            String body = String.valueOf(response.getOrDefault("body", ""));
            if (httpStatus == 405 || body.toLowerCase().contains("cannot_be_merged")) {
                log.warn("GitLab merge conflict for mr={}: {}", mrId, body);
                return MergeResult.conflict(List.of(
                    "GitLab MR " + mrId + " cannot be merged: " + truncate(body, 500)));
            }
            throw new RuntimeException("GitLab merge failed for mr=" + mrId
                + " (HTTP " + httpStatus + "): " + truncate(body, 500));
        }

        String mergeSha = String.valueOf(response.getOrDefault("merge_commit_sha",
            response.getOrDefault("sha", "")));
        String state = String.valueOf(response.getOrDefault("state", ""));
        if ("merged".equalsIgnoreCase(state) || !mergeSha.isBlank()) {
            return MergeResult.merged(mergeSha.isBlank() ? "0000000" : mergeSha);
        }
        // GitLab sometimes responds 200 with merge_status=cannot_be_merged.
        String mergeStatus = String.valueOf(response.getOrDefault("merge_status", ""));
        if ("cannot_be_merged".equalsIgnoreCase(mergeStatus)) {
            return MergeResult.conflict(List.of("merge_status=cannot_be_merged for mr=" + mrId));
        }
        // Unknown response — treat as success with the data we have rather than guess at conflicts.
        log.warn("GitLab merge returned ambiguous response for mr={}: {}", mrId, response);
        return MergeResult.merged(mergeSha.isBlank() ? "0000000" : mergeSha);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private GitLabClient client(Map<String, Object> config) {
        String url = (String) config.getOrDefault("url", config.getOrDefault("base_url", "https://gitlab.com"));
        String token = (String) config.getOrDefault("token", "");
        int projectId = toInt(config.get("project_id"));
        return new GitLabClient(url, token, projectId);
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        return 0;
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        return 0;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> emptyMr() { return new HashMap<>(); }
}
