package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.GitHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class GitHubPRBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(GitHubPRBlock.class);

    @Override
    public String getName() {
        return "github_pr";
    }

    @Override
    public String getDescription() {
        return "Создаёт ветку на GitHub, применяет все сгенерированные изменения, делает коммит и открывает Pull Request.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // Resolve codegen output: look for "codegen" key first, then other common keys
        Map<String, Object> codegenOutput = null;
        Object codegenObj = input.get("codegen");
        if (codegenObj instanceof Map) {
            codegenOutput = (Map<String, Object>) codegenObj;
        } else {
            for (String key : new String[]{"code_generation", "git_branch_input"}) {
                Object val = input.get(key);
                if (val instanceof Map) {
                    codegenOutput = (Map<String, Object>) val;
                    break;
                }
            }
        }
        if (codegenOutput == null && input.containsKey("branch_name")) {
            codegenOutput = input;
        }
        if (codegenOutput == null) {
            codegenOutput = new HashMap<>();
        }

        String branchName = (String) codegenOutput.getOrDefault("branch_name", "feature/generated");
        List<Map<String, Object>> changes = new ArrayList<>();
        if (codegenOutput.get("changes") instanceof List) {
            changes = (List<Map<String, Object>>) codegenOutput.get("changes");
        }
        List<Map<String, Object>> testChanges = new ArrayList<>();
        if (codegenOutput.get("test_changes") instanceof List) {
            testChanges = (List<Map<String, Object>>) codegenOutput.get("test_changes");
        }
        String commitMessage = (String) codegenOutput.getOrDefault("commit_message", "feat: generated changes");
        boolean changesAlreadyApplied = Boolean.TRUE.equals(codegenOutput.get("changes_already_applied"));

        List<Map<String, Object>> youtrackIssues = new ArrayList<>();
        if (codegenOutput.get("youtrack_issues") instanceof List) {
            youtrackIssues = (List<Map<String, Object>>) codegenOutput.get("youtrack_issues");
        }

        // Get GitHub config
        Object githubCfgObj = cfg.get("_github_config");
        if (!(githubCfgObj instanceof Map)) {
            log.warn("No _github_config found, returning placeholder PR result");
            return buildPlaceholderResult(branchName, commitMessage, youtrackIssues);
        }

        Map<String, Object> githubCfg = (Map<String, Object>) githubCfgObj;
        String token = (String) githubCfg.getOrDefault("token", "");
        String owner = (String) githubCfg.getOrDefault("owner", "");
        String repo = (String) githubCfg.getOrDefault("repo", "");
        String apiUrl = (String) githubCfg.getOrDefault("api_url", "https://api.github.com");
        String baseBranch = (String) githubCfg.getOrDefault("base_branch", "main");

        GitHubClient gitHubClient = new GitHubClient(token, owner, repo, apiUrl);

        // Create branch and apply changes if not already applied
        if (!changesAlreadyApplied) {
            try {
                gitHubClient.createBranch(branchName, baseBranch);
                log.info("Created GitHub branch: {}", branchName);
            } catch (Exception e) {
                log.warn("Branch creation failed (may already exist): {}", e.getMessage());
            }

            // Combine changes and test changes
            List<Map<String, Object>> allChanges = new ArrayList<>(changes);
            allChanges.addAll(testChanges);

            for (Map<String, Object> change : allChanges) {
                String filePath = (String) change.getOrDefault("file_path", "");
                String action = (String) change.getOrDefault("action", "modify");
                String content = (String) change.getOrDefault("content", "");

                if (filePath.isBlank()) continue;

                try {
                    if ("delete".equals(action)) {
                        gitHubClient.deleteFile(branchName, filePath, commitMessage);
                    } else {
                        gitHubClient.createOrUpdateFile(branchName, filePath, content, commitMessage);
                    }
                    log.info("Applied {} to {}", action, filePath);
                } catch (Exception e) {
                    log.error("Failed to apply change for file '{}': {}", filePath, e.getMessage());
                }
            }
        }

        // Build PR body
        StringBuilder changeList = new StringBuilder();
        for (Map<String, Object> change : changes) {
            changeList.append("- **").append(change.getOrDefault("action", "modify"))
                .append("** `").append(change.getOrDefault("file_path", ""))
                .append("`");
            String desc = (String) change.get("description");
            if (desc != null && !desc.isBlank()) {
                changeList.append(": ").append(desc);
            }
            changeList.append("\n");
        }

        StringBuilder issueLinksSb = new StringBuilder();
        List<String> issueIds = new ArrayList<>();
        for (Map<String, Object> issue : youtrackIssues) {
            String issueId = (String) issue.get("id");
            String issueUrl = (String) issue.get("url");
            String issueSummary = (String) issue.getOrDefault("summary", "");
            if (issueId != null) {
                issueIds.add(issueId);
                issueLinksSb.append("- [").append(issueId).append("](").append(issueUrl != null ? issueUrl : "")
                    .append(") ").append(issueSummary).append("\n");
            }
        }

        String prBody = buildPrBody(commitMessage, changeList.toString(), issueLinksSb.toString());

        // Create Pull Request
        Map<String, Object> pr;
        try {
            pr = gitHubClient.createPullRequest(commitMessage, prBody, branchName, baseBranch,
                List.of("workflow-generated"));
        } catch (Exception e) {
            log.error("Failed to create PR: {}", e.getMessage());
            throw new RuntimeException("Failed to create GitHub PR: " + e.getMessage(), e);
        }

        int prNumber = 0;
        if (pr.get("number") instanceof Number) prNumber = ((Number) pr.get("number")).intValue();
        String prUrl = (String) pr.getOrDefault("html_url", "");
        String createdAt = (String) pr.getOrDefault("created_at", Instant.now().toString());

        Map<String, Object> result = new HashMap<>();
        result.put("pr_number", prNumber);
        result.put("pr_url", prUrl);
        result.put("branch", branchName);
        result.put("title", commitMessage);
        result.put("youtrack_issues_linked", issueIds);
        result.put("created_at", createdAt);
        return result;
    }

    private String buildPrBody(String summary, String changeList, String issueLinks) {
        return "## Summary\n\n" + summary + "\n\n" +
               "## Changes\n\n" + (changeList.isBlank() ? "(no changes listed)" : changeList) + "\n\n" +
               "## Related Issues\n\n" + (issueLinks.isBlank() ? "(none)" : issueLinks) + "\n\n" +
               "## Test Plan\n\n" +
               "- CI pipeline will run automated tests.\n" +
               "- Please review all generated changes carefully.\n\n" +
               "---\n*This PR was created automatically by Workflow AI pipeline.*";
    }

    private Map<String, Object> buildPlaceholderResult(String branchName, String title,
                                                        List<Map<String, Object>> youtrackIssues) {
        List<String> issueIds = new ArrayList<>();
        for (Map<String, Object> issue : youtrackIssues) {
            String id = (String) issue.get("id");
            if (id != null) issueIds.add(id);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("pr_number", 0);
        result.put("pr_url", "(github not configured)");
        result.put("branch", branchName);
        result.put("title", title);
        result.put("youtrack_issues_linked", issueIds);
        result.put("created_at", Instant.now().toString());
        return result;
    }
}
