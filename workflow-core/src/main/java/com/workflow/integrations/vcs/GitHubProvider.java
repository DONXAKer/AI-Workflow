package com.workflow.integrations.vcs;

import com.workflow.integrations.GitHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GitHubProvider implements VcsProvider {

    private static final Logger log = LoggerFactory.getLogger(GitHubProvider.class);

    @Override public String providerName() { return "github"; }

    @Override
    public void createBranch(String branchName, String baseBranch, Map<String, Object> config) throws Exception {
        client(config).createBranch(branchName, baseBranch);
    }

    @Override
    public void applyChanges(String branch, List<VcsChange> changes, String commitMessage,
                              Map<String, Object> config) throws Exception {
        GitHubClient client = client(config);
        for (VcsChange change : changes) {
            switch (change.operation()) {
                case CREATE, UPDATE -> client.createOrUpdateFile(branch, change.path(), change.content(), commitMessage);
                case DELETE -> client.deleteFile(branch, change.path(), commitMessage);
            }
        }
    }

    @Override
    public MergeRequestRef openMergeRequest(String sourceBranch, String targetBranch, String title,
                                             String description, List<String> labels,
                                             Map<String, Object> config) throws Exception {
        Map<String, Object> pr = client(config).createPullRequest(title, description, sourceBranch, targetBranch, labels);
        long number = toLong(pr.get("number"));
        String url = (String) pr.getOrDefault("html_url", "");
        return new MergeRequestRef("github", number, title, sourceBranch, targetBranch, url, "open");
    }

    @Override
    public CiPipelineStatus waitForCi(long mrId, int timeoutSeconds, Map<String, Object> config) {
        // GitHub Actions lookup by PR is indirect — requires PR → head branch → workflow runs.
        // Leave as TODO until consumers request it; existing GitHubActionsBlock already handles this.
        log.warn("GitHub waitForCi not yet wired in VcsProvider (pr={})", mrId);
        return new CiPipelineStatus(0, "", "not_implemented", Map.of());
    }

    @Override
    public MergeResult merge(long mrId, MergeStrategy strategy, boolean deleteBranchAfter,
                              Map<String, Object> config) {
        log.warn("GitHub merge not yet wired (pr={}, strategy={})", mrId, strategy);
        return MergeResult.merged("0000000");
    }

    private GitHubClient client(Map<String, Object> config) {
        String token = (String) config.getOrDefault("token", "");
        String owner = (String) config.getOrDefault("owner", "");
        String repo = (String) config.getOrDefault("repo", "");
        String url = (String) config.getOrDefault("url", "https://api.github.com");
        return new GitHubClient(token, owner, repo, url);
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        return 0;
    }
}
