package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.GitLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GitLabMRBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(GitLabMRBlock.class);

    @Override
    public String getName() {
        return "gitlab_mr";
    }

    @Override
    public String getDescription() {
        return "Создаёт ветку в GitLab, применяет все сгенерированные изменения, делает коммит и открывает Merge Request со ссылками на задачи YouTrack.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // Resolve codegen output: look for "codegen" key first, then check if input has branch_name
        Map<String, Object> codegenOutput = null;
        Object codegenObj = input.get("codegen");
        if (codegenObj instanceof Map) {
            codegenOutput = (Map<String, Object>) codegenObj;
        } else {
            // Try other common keys
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

        // Get GitLab config
        Object gitlabCfgObj = cfg.get("_gitlab_config");
        if (!(gitlabCfgObj instanceof Map)) {
            log.warn("No _gitlab_config found, returning placeholder MR result");
            return buildPlaceholderResult(branchName, commitMessage, youtrackIssues);
        }

        Map<String, Object> gitlabCfg = (Map<String, Object>) gitlabCfgObj;
        String baseUrl = (String) gitlabCfg.getOrDefault("base_url", "https://gitlab.com");
        String token = (String) gitlabCfg.getOrDefault("token", "");
        Object projectIdObj = gitlabCfg.get("project_id");
        int projectId = 0;
        if (projectIdObj instanceof Number) {
            projectId = ((Number) projectIdObj).intValue();
        } else if (projectIdObj instanceof String) {
            try { projectId = Integer.parseInt((String) projectIdObj); } catch (NumberFormatException ignored) {}
        }
        String targetBranch = (String) gitlabCfg.getOrDefault("target_branch", "main");

        GitLabClient gitLabClient = new GitLabClient(baseUrl, token, projectId);

        // Apply changes to GitLab if not already applied
        if (!changesAlreadyApplied) {
            try {
                gitLabClient.createBranch(branchName, targetBranch);
                log.info("Created GitLab branch: {}", branchName);
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
                    switch (action) {
                        case "create" -> gitLabClient.createFile(branchName, filePath, content, commitMessage);
                        case "delete" -> gitLabClient.deleteFile(branchName, filePath, commitMessage);
                        default -> {
                            try {
                                gitLabClient.updateFile(branchName, filePath, content, commitMessage);
                            } catch (Exception ex) {
                                // Fall back to create if file doesn't exist
                                gitLabClient.createFile(branchName, filePath, content, commitMessage);
                            }
                        }
                    }
                    log.info("Applied {} to {}", action, filePath);
                } catch (Exception e) {
                    log.error("Failed to apply change for file '{}': {}", filePath, e.getMessage());
                }
            }
        }

        // Build MR description
        String summary = commitMessage;
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

        String mrDescription = buildMrDescription(summary, changeList.toString(), issueLinksSb.toString());

        // Create Merge Request
        String mrTitle = commitMessage;
        Map<String, Object> mr;
        try {
            mr = gitLabClient.createMergeRequest(branchName, mrTitle, mrDescription,
                List.of("workflow-generated"), targetBranch);
        } catch (Exception e) {
            log.error("Failed to create MR: {}", e.getMessage());
            throw new RuntimeException("Failed to create GitLab MR: " + e.getMessage(), e);
        }

        int mrId = 0;
        if (mr.get("iid") instanceof Number) mrId = ((Number) mr.get("iid")).intValue();
        String mrUrl = (String) mr.getOrDefault("web_url", "");

        Map<String, Object> result = new HashMap<>();
        result.put("mr_id", mrId);
        result.put("mr_url", mrUrl);
        result.put("branch", branchName);
        result.put("title", mrTitle);
        result.put("youtrack_issues_linked", issueIds);
        return result;
    }

    private String buildMrDescription(String summary, String changeList, String issueLinks) {
        return "## Summary\n\n" + summary + "\n\n" +
               "## Changes\n\n" + (changeList.isBlank() ? "(no changes listed)" : changeList) + "\n\n" +
               "## Related Issues\n\n" + (issueLinks.isBlank() ? "(none)" : issueLinks) + "\n\n" +
               "## Test Plan\n\n" +
               "- CI pipeline will run automated tests.\n" +
               "- Please review all generated changes carefully.\n\n" +
               "---\n*This MR was created automatically by Workflow AI pipeline.*";
    }

    private Map<String, Object> buildPlaceholderResult(String branchName, String title,
                                                        List<Map<String, Object>> youtrackIssues) {
        List<String> issueIds = new ArrayList<>();
        for (Map<String, Object> issue : youtrackIssues) {
            String id = (String) issue.get("id");
            if (id != null) issueIds.add(id);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("mr_id", 0);
        result.put("mr_url", "(gitlab not configured)");
        result.put("branch", branchName);
        result.put("title", title);
        result.put("youtrack_issues_linked", issueIds);
        return result;
    }
}
