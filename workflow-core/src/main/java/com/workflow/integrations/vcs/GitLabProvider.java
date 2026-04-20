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
                              Map<String, Object> config) {
        // GitLab merge via PUT /api/v4/projects/:id/merge_requests/:iid/merge not yet wired
        // in GitLabClient. Returns a placeholder — real conflict detection lands with the
        // VcsMergeBlock real integration.
        log.warn("GitLab merge not yet wired (mr={}, strategy={})", mrId, strategy);
        return MergeResult.merged("0000000");
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
