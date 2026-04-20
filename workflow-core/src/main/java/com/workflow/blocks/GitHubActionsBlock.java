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
public class GitHubActionsBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(GitHubActionsBlock.class);

    @Override
    public String getName() {
        return "github_actions";
    }

    @Override
    public String getDescription() {
        return "Отслеживает запуски GitHub Actions на ветке PR, ждёт завершения всех workflow и сообщает результаты.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // Resolve PR data from "pr" key or other common keys
        Map<String, Object> prData = null;
        Object prObj = input.get("pr");
        if (prObj instanceof Map) {
            prData = (Map<String, Object>) prObj;
        } else {
            for (String key : new String[]{"github_pr"}) {
                Object val = input.get(key);
                if (val instanceof Map) {
                    prData = (Map<String, Object>) val;
                    break;
                }
            }
        }
        if (prData == null && input.containsKey("pr_number")) {
            prData = input;
        }
        if (prData == null) {
            prData = new HashMap<>();
        }

        String branch = (String) prData.getOrDefault("branch", "");
        int prNumber = 0;
        Object prNumObj = prData.get("pr_number");
        if (prNumObj instanceof Number) prNumber = ((Number) prNumObj).intValue();

        // Get GitHub config
        Object githubCfgObj = cfg.get("_github_config");
        if (!(githubCfgObj instanceof Map)) {
            log.warn("No _github_config found in GitHubActionsBlock, returning placeholder");
            Map<String, Object> result = new HashMap<>();
            result.put("runs", new ArrayList<>());
            result.put("overall_status", "unknown");
            result.put("branch", branch);
            result.put("pr_number", prNumber);
            return result;
        }

        Map<String, Object> githubCfg = (Map<String, Object>) githubCfgObj;
        String token = (String) githubCfg.getOrDefault("token", "");
        String owner = (String) githubCfg.getOrDefault("owner", "");
        String repo = (String) githubCfg.getOrDefault("repo", "");
        String apiUrl = (String) githubCfg.getOrDefault("api_url", "https://api.github.com");

        int timeoutSeconds = 600;
        Object timeoutObj = cfg.get("timeout_seconds");
        if (timeoutObj instanceof Number) {
            timeoutSeconds = ((Number) timeoutObj).intValue();
        }

        // Workflow files to watch (optional)
        List<String> workflowFiles = new ArrayList<>();
        Object wfObj = cfg.get("workflow_files");
        if (wfObj instanceof List) {
            for (Object wf : (List<?>) wfObj) {
                if (wf instanceof String) workflowFiles.add((String) wf);
            }
        }
        if (workflowFiles.isEmpty()) {
            workflowFiles.add(null); // null means "any workflow"
        }

        GitHubClient gitHubClient = new GitHubClient(token, owner, repo, apiUrl);

        String triggeredAfter = Instant.now().toString();

        // Optionally trigger a workflow
        Object triggerObj = cfg.get("trigger_workflow");
        if (triggerObj instanceof Map) {
            Map<String, Object> triggerCfg = (Map<String, Object>) triggerObj;
            String workflowFile = (String) triggerCfg.getOrDefault("workflow_file", "");
            String ref = branch.isBlank() ? "main" : branch;
            Object inputsObj = triggerCfg.get("inputs");
            Map<String, Object> workflowInputs = new HashMap<>();
            if (inputsObj instanceof Map) {
                workflowInputs = (Map<String, Object>) inputsObj;
            }
            if (!workflowFile.isBlank()) {
                try {
                    gitHubClient.triggerWorkflow(workflowFile, ref, workflowInputs);
                    log.info("Triggered workflow {} on ref {}", workflowFile, ref);
                    // Small delay to allow workflow to register
                    Thread.sleep(3000);
                } catch (Exception e) {
                    log.warn("Failed to trigger workflow {}: {}", workflowFile, e.getMessage());
                }
            }
        }

        log.info("Waiting for GitHub Actions on branch '{}' (timeout={}s)", branch, timeoutSeconds);

        List<Map<String, Object>> completedRuns = gitHubClient.waitForWorkflowRuns(
            branch.isBlank() ? null : branch,
            timeoutSeconds,
            workflowFiles,
            triggeredAfter
        );

        // If wait timed out or no runs found, try to get what we can
        if (completedRuns.isEmpty()) {
            log.warn("No completed workflow runs found within timeout");
            List<Map<String, Object>> currentRuns = new ArrayList<>();
            for (String wf : workflowFiles) {
                try {
                    List<Map<String, Object>> runs = gitHubClient.getWorkflowRuns(
                        branch.isBlank() ? null : branch, wf, null);
                    currentRuns.addAll(runs);
                } catch (Exception e) {
                    log.warn("Failed to get workflow runs: {}", e.getMessage());
                }
            }
            completedRuns = currentRuns;
        }

        // Calculate overall status from conclusions
        String overallStatus = calculateOverallStatus(completedRuns);

        // Build simplified run summaries
        List<Map<String, Object>> runSummaries = new ArrayList<>();
        for (Map<String, Object> run2 : completedRuns) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("id", run2.get("id"));
            summary.put("name", run2.getOrDefault("name", ""));
            summary.put("status", run2.getOrDefault("status", ""));
            summary.put("conclusion", run2.getOrDefault("conclusion", ""));
            summary.put("html_url", run2.getOrDefault("html_url", ""));
            summary.put("created_at", run2.getOrDefault("created_at", ""));
            summary.put("updated_at", run2.getOrDefault("updated_at", ""));
            runSummaries.add(summary);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("runs", runSummaries);
        result.put("overall_status", overallStatus);
        result.put("branch", branch);
        result.put("pr_number", prNumber);
        return result;
    }

    private String calculateOverallStatus(List<Map<String, Object>> runs) {
        if (runs.isEmpty()) return "no_runs";

        boolean allSuccess = true;
        boolean anyFailure = false;

        for (Map<String, Object> run : runs) {
            String conclusion = (String) run.getOrDefault("conclusion", "");
            String status = (String) run.getOrDefault("status", "");

            if (!"completed".equals(status)) {
                allSuccess = false;
                continue;
            }

            switch (conclusion) {
                case "success", "skipped" -> {}
                case "failure", "timed_out", "startup_failure" -> {
                    allSuccess = false;
                    anyFailure = true;
                }
                case "cancelled", "neutral", "action_required" -> {
                    allSuccess = false;
                }
                default -> allSuccess = false;
            }
        }

        if (anyFailure) return "failure";
        if (allSuccess) return "success";
        return "partial";
    }
}
