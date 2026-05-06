package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.GitLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GitBranchInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(GitBranchInputBlock.class);

    @Override
    public String getName() {
        return "git_branch_input";
    }

    @Override
    public String getDescription() {
        return "Читает существующую ветку GitLab (diff относительно целевой ветки) и формирует такой же вывод как code_generation.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Git branch input",
            "input",
            Phase.INTAKE,
            List.of(
                FieldSchema.requiredString("branch", "Имя ветки",
                    "Существующая ветка в GitLab проекта."),
                new FieldSchema("target_branch", "Целевая ветка", "string", false, "main",
                    "Ветка, относительно которой берётся diff (по умолчанию main).", Map.of())
            ),
            false,
            Map.of()
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        String branch = (String) cfg.getOrDefault("branch", "");
        String targetBranch = (String) cfg.getOrDefault("target_branch", "main");

        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("GitBranchInputBlock requires 'branch' in config");
        }

        // Get GitLab config
        Object gitlabCfgObj = cfg.get("_gitlab_config");
        if (!(gitlabCfgObj instanceof Map)) {
            log.warn("No _gitlab_config found, returning placeholder for branch: {}", branch);
            return buildPlaceholderResult(branch);
        }

        Map<String, Object> gitlabCfg = (Map<String, Object>) gitlabCfgObj;
        String baseUrl = (String) gitlabCfg.getOrDefault("base_url", "https://gitlab.com");
        String token = (String) gitlabCfg.getOrDefault("token", "");
        Object projectIdObj = gitlabCfg.get("project_id");
        int projectId = 0;
        if (projectIdObj instanceof Number) {
            projectId = ((Number) projectIdObj).intValue();
        } else if (projectIdObj instanceof String) {
            try {
                projectId = Integer.parseInt((String) projectIdObj);
            } catch (NumberFormatException e) {
                log.warn("Invalid project_id: {}", projectIdObj);
            }
        }

        GitLabClient gitLabClient = new GitLabClient(baseUrl, token, projectId);

        List<Map<String, Object>> diffs = gitLabClient.compareBranches(targetBranch, branch);

        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> testChanges = new ArrayList<>();

        for (Map<String, Object> diff : diffs) {
            String filePath = (String) diff.getOrDefault("new_path", diff.getOrDefault("old_path", ""));
            String diffContent = (String) diff.getOrDefault("diff", "");
            Boolean deletedFile = (Boolean) diff.getOrDefault("deleted_file", false);
            Boolean newFile = (Boolean) diff.getOrDefault("new_file", false);

            String action;
            if (Boolean.TRUE.equals(deletedFile)) {
                action = "delete";
            } else if (Boolean.TRUE.equals(newFile)) {
                action = "create";
            } else {
                action = "modify";
            }

            Map<String, Object> change = new HashMap<>();
            change.put("file_path", filePath);
            change.put("action", action);
            change.put("content", diffContent);
            change.put("description", "Change from branch " + branch);

            if (isTestFile(filePath)) {
                testChanges.add(change);
            } else {
                changes.add(change);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("branch_name", branch);
        result.put("changes", changes);
        result.put("test_changes", testChanges);
        result.put("commit_message", "Changes from branch: " + branch);
        result.put("tasks_generated", 0);
        result.put("youtrack_issues", new ArrayList<>());
        result.put("changes_already_applied", true);
        return result;
    }

    private boolean isTestFile(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        // Check if path contains test/tests directory segment or filename starts with test_
        String[] parts = lower.replace("\\", "/").split("/");
        for (String part : parts) {
            if ("test".equals(part) || "tests".equals(part)) return true;
        }
        // Check filename
        String filename = parts[parts.length - 1];
        return filename.startsWith("test_") || filename.endsWith("_test.py") ||
               filename.endsWith("test.java") || filename.endsWith("Tests.java") ||
               filename.endsWith("Test.java") || filename.endsWith(".test.ts") ||
               filename.endsWith(".spec.ts") || filename.endsWith(".test.js") ||
               filename.endsWith(".spec.js");
    }

    private Map<String, Object> buildPlaceholderResult(String branch) {
        Map<String, Object> result = new HashMap<>();
        result.put("branch_name", branch);
        result.put("changes", new ArrayList<>());
        result.put("test_changes", new ArrayList<>());
        result.put("commit_message", "Changes from branch: " + branch);
        result.put("tasks_generated", 0);
        result.put("youtrack_issues", new ArrayList<>());
        result.put("changes_already_applied", true);
        return result;
    }
}
