package com.workflow.core;

import com.workflow.config.EntryPointConfig;
import com.workflow.config.EntryPointInjection;
import com.workflow.config.IntegrationsConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.integrations.GitHubClient;
import com.workflow.integrations.GitLabClient;
import com.workflow.integrations.YouTrackClient;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Resolves entry-point injections and runs auto-detection of pipeline stage.
 *
 * <p>For each {@link EntryPointInjection} declared in an {@link EntryPointConfig}, this service
 * fetches the real data from YouTrack / GitLab / GitHub and returns it as a ready-to-use
 * {@code Map<blockId, outputMap>} that can be passed straight into
 * {@link PipelineRunner#runFrom}.
 */
@Service
public class EntryPointResolver {

    private static final Logger log = LoggerFactory.getLogger(EntryPointResolver.class);

    @Autowired
    private IntegrationConfigRepository integrationConfigRepository;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resolves all injections declared in {@code ep} using the supplied user inputs.
     *
     * @param ep              entry point whose {@code inject} list is processed
     * @param userInputs      values provided by the user at run start time:
     *                        {@code youtrackIssue}, {@code branchName}, {@code mrIid}
     * @param pipelineConfig  pipeline config (used to look up integration credentials)
     * @return map of blockId → output map ready for injection
     */
    public Map<String, Map<String, Object>> resolveInjections(
            EntryPointConfig ep,
            Map<String, Object> userInputs,
            PipelineConfig pipelineConfig) throws Exception {

        Map<String, Map<String, Object>> result = new HashMap<>();
        if (ep.getInject() == null || ep.getInject().isEmpty()) return result;

        IntegrationsConfig integrations = pipelineConfig.getIntegrations();

        for (EntryPointInjection inj : ep.getInject()) {
            String blockId = inj.getBlockId();
            String source  = inj.getSource();

            if (source == null || source.isBlank()) {
                result.put(blockId, new HashMap<>());
                continue;
            }

            Map<String, Object> output = switch (source) {
                case "empty" -> new HashMap<>();
                case "youtrack" -> resolveYouTrack(inj, userInputs, integrations);
                case "youtrack_tasks" -> resolveYouTrackTasks(inj, userInputs, integrations);
                case "gitlab_branch" -> resolveGitLabBranch(inj, userInputs, integrations);
                case "gitlab_mr"     -> resolveGitLabMr(inj, userInputs, integrations);
                case "github_pr"     -> resolveGitHubPr(inj, userInputs, integrations);
                default -> {
                    log.warn("Unknown injection source '{}' for block '{}', using empty", source, blockId);
                    yield new HashMap<>();
                }
            };

            result.put(blockId, output);
            log.debug("Resolved injection for block '{}' (source={}): {} keys", blockId, source, output.size());
        }
        return result;
    }

    /**
     * Auto-detects the most appropriate entry point for the given YouTrack issue.
     *
     * <p>Detection order (highest wins):
     * <ol>
     *   <li>Open MR/PR exists → {@code mr_open}
     *   <li>Feature branch exists → {@code branch_exists}
     *   <li>Subtasks exist in YouTrack → {@code tasks_exist}
     *   <li>Nothing found → {@code from_scratch}
     * </ol>
     *
     * @param youtrackIssue  issue key, e.g. {@code PROJ-42}
     * @param pipelineConfig pipeline config (used to look up integration credentials)
     * @return detection result with suggested entry point id and detected artefacts
     */
    public DetectionResult autoDetect(String youtrackIssue, PipelineConfig pipelineConfig) {
        IntegrationsConfig integrations = pipelineConfig.getIntegrations();
        Map<String, Object> detected = new LinkedHashMap<>();
        String suggestedEntryPoint = "from_scratch";

        // 1. Check YouTrack subtasks
        try {
            Optional<IntegrationConfig> ytCfg = resolveIntegration(
                integrations != null ? integrations.getYoutrack() : null, IntegrationType.YOUTRACK);
            if (ytCfg.isPresent()) {
                IntegrationConfig cfg = ytCfg.get();
                YouTrackClient yt = new YouTrackClient(cfg.getBaseUrl(), cfg.getToken(), cfg.getProject());
                List<Map<String, Object>> subtasks = yt.getSubtasks(youtrackIssue);
                if (!subtasks.isEmpty()) {
                    detected.put("youtrackSubtasks", subtasks.size());
                    suggestedEntryPoint = "tasks_exist";
                    log.info("auto-detect: found {} subtasks for {}", subtasks.size(), youtrackIssue);
                }
            }
        } catch (Exception e) {
            log.warn("auto-detect: failed to check YouTrack subtasks: {}", e.getMessage());
        }

        // 2. Check GitLab branch / MR
        try {
            Optional<IntegrationConfig> glCfg = resolveIntegration(
                integrations != null ? integrations.getGitlab() : null, IntegrationType.GITLAB);
            if (glCfg.isPresent()) {
                IntegrationConfig cfg = glCfg.get();
                GitLabClient gl = buildGitLabClient(cfg);
                List<String> branches = gl.findBranchesForIssue(youtrackIssue);
                if (!branches.isEmpty()) {
                    String branch = branches.get(0);
                    detected.put("gitlabBranch", branch);
                    suggestedEntryPoint = "branch_exists";
                    log.info("auto-detect: found GitLab branch '{}' for {}", branch, youtrackIssue);

                    // Check for open MR on that branch
                    Map<String, Object> mr = gl.getMergeRequestByBranch(branch);
                    if (mr != null) {
                        detected.put("gitlabMrIid", mr.get("iid"));
                        detected.put("gitlabMrUrl", mr.get("web_url"));
                        suggestedEntryPoint = "mr_open";
                        log.info("auto-detect: found open MR {} for branch '{}'", mr.get("iid"), branch);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("auto-detect: failed to check GitLab: {}", e.getMessage());
        }

        // 3. Check GitHub branch / PR (only if GitLab didn't find anything)
        if ("from_scratch".equals(suggestedEntryPoint) || "tasks_exist".equals(suggestedEntryPoint)) {
            try {
                Optional<IntegrationConfig> ghCfg = resolveIntegration(
                    integrations != null ? integrations.getGithub() : null, IntegrationType.GITHUB);
                if (ghCfg.isPresent()) {
                    IntegrationConfig cfg = ghCfg.get();
                    GitHubClient gh = new GitHubClient(cfg.getToken(), cfg.getOwner(), cfg.getRepo(), cfg.getBaseUrl());
                    List<String> branches = gh.findBranchesForIssue(youtrackIssue);
                    if (!branches.isEmpty()) {
                        String branch = branches.get(0);
                        detected.put("githubBranch", branch);
                        suggestedEntryPoint = "branch_exists";
                        log.info("auto-detect: found GitHub branch '{}' for {}", branch, youtrackIssue);

                        Map<String, Object> pr = gh.getPullRequestByBranch(branch);
                        if (pr != null) {
                            detected.put("githubPrNumber", pr.get("number"));
                            detected.put("githubPrUrl", pr.get("html_url"));
                            suggestedEntryPoint = "mr_open";
                            log.info("auto-detect: found open PR #{} for branch '{}'", pr.get("number"), branch);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("auto-detect: failed to check GitHub: {}", e.getMessage());
            }
        }

        return new DetectionResult(suggestedEntryPoint, detected);
    }

    // -------------------------------------------------------------------------
    // Source resolvers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveYouTrack(EntryPointInjection inj, Map<String, Object> userInputs,
                                                  IntegrationsConfig integrations) throws Exception {
        String issueId = resolveString(inj, userInputs, "youtrackIssue");
        if (issueId == null || issueId.isBlank()) {
            log.warn("resolveYouTrack: no youtrackIssue in userInputs or injection config, returning empty");
            return new HashMap<>();
        }

        IntegrationConfig cfg = resolveIntegration(
            integrations != null ? integrations.getYoutrack() : null, IntegrationType.YOUTRACK)
            .orElseThrow(() -> new IllegalStateException("No YouTrack integration configured"));

        YouTrackClient yt = new YouTrackClient(cfg.getBaseUrl(), cfg.getToken(), cfg.getProject());
        Map<String, Object> issue = yt.getIssue(issueId);

        String summary = (String) issue.getOrDefault("summary", "");
        String description = (String) issue.getOrDefault("description", "");
        String idReadable = (String) issue.getOrDefault("idReadable", issueId);

        StringBuilder req = new StringBuilder();
        req.append("# ").append(summary).append("\n\n");
        if (description != null && !description.isBlank()) req.append(description).append("\n\n");

        Map<String, Object> sourceIssue = new HashMap<>();
        sourceIssue.put("id", idReadable);
        sourceIssue.put("url", cfg.getBaseUrl() + "/issue/" + idReadable);
        sourceIssue.put("summary", summary);

        Map<String, Object> result = new HashMap<>();
        result.put("requirement", req.toString().trim());
        result.put("task_mode", "decompose");
        result.put("youtrack_source_issue", sourceIssue);
        return result;
    }

    private Map<String, Object> resolveYouTrackTasks(EntryPointInjection inj, Map<String, Object> userInputs,
                                                       IntegrationsConfig integrations) throws Exception {
        String issueId = resolveString(inj, userInputs, "youtrackIssue");
        if (issueId == null || issueId.isBlank()) {
            log.warn("resolveYouTrackTasks: no youtrackIssue, returning empty tasks");
            return Map.of("tasks", new ArrayList<>(), "youtrack_issues", new ArrayList<>());
        }

        IntegrationConfig cfg = resolveIntegration(
            integrations != null ? integrations.getYoutrack() : null, IntegrationType.YOUTRACK)
            .orElseThrow(() -> new IllegalStateException("No YouTrack integration configured"));

        YouTrackClient yt = new YouTrackClient(cfg.getBaseUrl(), cfg.getToken(), cfg.getProject());
        List<Map<String, Object>> subtasks = yt.getSubtasks(issueId);

        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> youtrackIssues = new ArrayList<>();

        for (Map<String, Object> subtask : subtasks) {
            String subId = (String) subtask.getOrDefault("idReadable", subtask.getOrDefault("id", ""));
            String summary = (String) subtask.getOrDefault("summary", "");

            Map<String, Object> task = new HashMap<>();
            task.put("summary", summary);
            task.put("description", "");
            task.put("type", "Task");
            task.put("priority", "Normal");
            task.put("estimated_hours", 0);
            tasks.add(task);

            Map<String, Object> ref = new HashMap<>();
            ref.put("id", subId);
            ref.put("url", cfg.getBaseUrl() + "/issue/" + subId);
            ref.put("summary", summary);
            youtrackIssues.add(ref);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("youtrack_issues", youtrackIssues);
        return result;
    }

    private Map<String, Object> resolveGitLabBranch(EntryPointInjection inj, Map<String, Object> userInputs,
                                                      IntegrationsConfig integrations) throws Exception {
        String branch = resolveString(inj, userInputs, "branchName");
        String targetBranch = (String) inj.getConfig().getOrDefault("target_branch", "main");

        if (branch == null || branch.isBlank()) {
            log.warn("resolveGitLabBranch: no branchName, returning placeholder");
            return buildBranchPlaceholder("unknown");
        }

        IntegrationConfig cfg = resolveIntegration(
            integrations != null ? integrations.getGitlab() : null, IntegrationType.GITLAB)
            .orElseThrow(() -> new IllegalStateException("No GitLab integration configured"));

        GitLabClient gl = buildGitLabClient(cfg);
        List<Map<String, Object>> diffs = gl.compareBranches(targetBranch, branch);

        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> testChanges = new ArrayList<>();

        for (Map<String, Object> diff : diffs) {
            String filePath = (String) diff.getOrDefault("new_path", diff.getOrDefault("old_path", ""));
            String action = Boolean.TRUE.equals(diff.get("deleted_file")) ? "delete"
                          : Boolean.TRUE.equals(diff.get("new_file"))     ? "create"
                          : "modify";

            Map<String, Object> change = new HashMap<>();
            change.put("file_path", filePath);
            change.put("action", action);
            change.put("content", diff.getOrDefault("diff", ""));
            change.put("description", "Change from branch " + branch);

            (isTestFile(filePath) ? testChanges : changes).add(change);
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

    private Map<String, Object> resolveGitLabMr(EntryPointInjection inj, Map<String, Object> userInputs,
                                                  IntegrationsConfig integrations) throws Exception {
        IntegrationConfig cfg = resolveIntegration(
            integrations != null ? integrations.getGitlab() : null, IntegrationType.GITLAB)
            .orElseThrow(() -> new IllegalStateException("No GitLab integration configured"));

        GitLabClient gl = buildGitLabClient(cfg);

        Map<String, Object> mr;
        Object mrIidRaw = userInputs.get("mrIid");
        if (mrIidRaw instanceof Number) {
            mr = gl.getMergeRequest(((Number) mrIidRaw).intValue());
        } else {
            String branch = resolveString(inj, userInputs, "branchName");
            if (branch == null || branch.isBlank()) {
                throw new IllegalArgumentException("resolveGitLabMr: need mrIid or branchName");
            }
            mr = gl.getMergeRequestByBranch(branch);
            if (mr == null) throw new IllegalStateException("No open MR found for branch: " + branch);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("mr_id", mr.get("iid"));
        result.put("mr_url", mr.getOrDefault("web_url", ""));
        result.put("branch", mr.getOrDefault("source_branch", ""));
        result.put("title", mr.getOrDefault("title", ""));
        result.put("youtrack_issues_linked", new ArrayList<>());
        return result;
    }

    private Map<String, Object> resolveGitHubPr(EntryPointInjection inj, Map<String, Object> userInputs,
                                                  IntegrationsConfig integrations) throws Exception {
        IntegrationConfig cfg = resolveIntegration(
            integrations != null ? integrations.getGithub() : null, IntegrationType.GITHUB)
            .orElseThrow(() -> new IllegalStateException("No GitHub integration configured"));

        GitHubClient gh = new GitHubClient(cfg.getToken(), cfg.getOwner(), cfg.getRepo(), cfg.getBaseUrl());

        Map<String, Object> pr;
        Object prNumberRaw = userInputs.get("mrIid");
        if (prNumberRaw instanceof Number) {
            pr = gh.getPullRequest(((Number) prNumberRaw).intValue());
        } else {
            String branch = resolveString(inj, userInputs, "branchName");
            if (branch == null || branch.isBlank()) {
                throw new IllegalArgumentException("resolveGitHubPr: need mrIid (PR number) or branchName");
            }
            pr = gh.getPullRequestByBranch(branch);
            if (pr == null) throw new IllegalStateException("No open PR found for branch: " + branch);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("mr_id", pr.get("number"));
        result.put("mr_url", pr.getOrDefault("html_url", ""));
        Object headObj = pr.get("head");
        String prBranch = "";
        if (headObj instanceof Map) {
            Object ref = ((Map<?, ?>) headObj).get("ref");
            if (ref instanceof String) prBranch = (String) ref;
        }
        result.put("branch", prBranch);
        result.put("title", pr.getOrDefault("title", ""));
        result.put("youtrack_issues_linked", new ArrayList<>());
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns value from injection config first, then from userInputs by key. */
    private String resolveString(EntryPointInjection inj, Map<String, Object> userInputs, String key) {
        Object fromConfig = inj.getConfig().get(key);
        if (fromConfig instanceof String s && !s.isBlank()) return s;
        Object fromInput = userInputs.get(key);
        return fromInput instanceof String s ? s : null;
    }

    private Optional<IntegrationConfig> resolveIntegration(String configName, IntegrationType type) {
        String scope = com.workflow.project.ProjectContext.get();
        if (configName != null && !configName.isBlank()) {
            Optional<IntegrationConfig> scoped =
                integrationConfigRepository.findByNameAndProjectSlug(configName, scope);
            if (scoped.isPresent()) return scoped;
            return integrationConfigRepository.findByName(configName);
        }
        Optional<IntegrationConfig> scoped =
            integrationConfigRepository.findByTypeAndIsDefaultTrueAndProjectSlug(type, scope);
        if (scoped.isPresent()) return scoped;
        return integrationConfigRepository.findByTypeAndIsDefaultTrue(type);
    }

    private GitLabClient buildGitLabClient(IntegrationConfig cfg) {
        int projectId = 0;
        if (cfg.getProject() != null) {
            try { projectId = Integer.parseInt(cfg.getProject()); } catch (NumberFormatException ignored) {}
        }
        return new GitLabClient(cfg.getBaseUrl(), cfg.getToken(), projectId);
    }

    private static boolean isTestFile(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase().replace("\\", "/");
        String[] parts = lower.split("/");
        for (String part : parts) {
            if ("test".equals(part) || "tests".equals(part)) return true;
        }
        String filename = parts[parts.length - 1];
        return filename.startsWith("test_") || filename.endsWith("_test.py")
            || filename.endsWith("test.java") || filename.endsWith("Tests.java")
            || filename.endsWith("Test.java") || filename.endsWith(".test.ts")
            || filename.endsWith(".spec.ts")  || filename.endsWith(".test.js")
            || filename.endsWith(".spec.js");
    }

    private static Map<String, Object> buildBranchPlaceholder(String branch) {
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

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public record DetectionResult(String suggestedEntryPointId, Map<String, Object> detected) {}
}
