package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.tracker.TaskTracker;
import com.workflow.integrations.tracker.TaskTrackerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a comment + status update back to the task tracker.
 *
 * <p>Two modes:</p>
 * <ol>
 *   <li><b>Handoff</b> (agent-sdlc): extracts branch/PR URL from {@code publish.final_text}
 *       and writes a structured handoff comment. {@code status} sets the target state.</li>
 *   <li><b>Verdict</b> (intent-verify): reads {@code adversarial.passed}/{@code adversarial.issues},
 *       posts a verdict comment. {@code status_passed}/{@code status_failed} control the state
 *       transition. Activated by {@code mode: verdict} in config.</li>
 * </ol>
 *
 * <p>Common config:
 * <pre>
 * - id: task_handoff
 *   block: task_update
 *   config:
 *     status: "In Review"           # target status (handoff mode)
 *     status_passed: "Done"         # target status when verdict passed (verdict mode)
 *     status_failed: "In Progress"  # target status when verdict failed (verdict mode)
 *     mode: verdict                 # omit for handoff (default)
 *     provider: youtrack            # resolved from task_input.provider if omitted
 *     issue_id: ""                  # explicit override; usually taken from task_input.issue.readableId
 *     comment: ""                   # optional explicit comment text (overrides auto-generated)
 * </pre>
 */
@Component
public class TaskUpdateBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(TaskUpdateBlock.class);

    private static final Pattern PR_URL_PATTERN = Pattern.compile(
        "https?://[\\w.-]+(?::\\d+)?/[\\w./-]+/(?:pull|merge_requests|pulls)/\\d+[\\S]*",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BRANCH_PATTERN = Pattern.compile(
        "(?:branch[:\\s]+|git push -u origin |checkout -b |switch -c )([\\w./-]+)",
        Pattern.CASE_INSENSITIVE
    );

    @Autowired
    private TaskTrackerRegistry trackerRegistry;

    @Override
    public String getName() { return "task_update"; }

    @Override
    public String getDescription() {
        return "Записывает handoff-комментарий (ветка, PR/MR URL) в задачу таск-менеджера и обновляет статус.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Task update (handoff)",
            "output",
            Phase.PUBLISH,
            List.of(
                FieldSchema.string("status", "Целевой статус",
                    "Статус задачи после открытия PR/MR (например: 'In Review'). Зависит от провайдера."),
                FieldSchema.string("provider", "Провайдер",
                    "Тип трекера: youtrack / jira / github / gitlab (по умолчанию — из task_input.provider)."),
                FieldSchema.string("issue_id", "ID задачи",
                    "Явный ID задачи. Если пусто — берётся из task_input.issue.readableId.")
            ),
            false,
            Map.of(),
            List.of(
                FieldSchema.output("issue_id", "Issue ID", "string", "ID задачи в трекере."),
                FieldSchema.output("pr_url", "PR/MR URL", "string", "Ссылка на PR/MR, записанная в задачу."),
                FieldSchema.output("status_updated", "Status updated", "boolean",
                    "true если статус успешно изменён."),
                FieldSchema.output("comment_posted", "Comment posted", "boolean",
                    "true если комментарий успешно опубликован.")
            ),
            50
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run)
            throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // --- Resolve issue ID ---
        String issueId = stringFrom(cfg, "issue_id");
        if (issueId == null) issueId = stringFrom(input, "issue_id");
        if (issueId == null) {
            Object taskIssue = input.get("task_input");
            if (taskIssue instanceof Map<?, ?> ti) {
                Object issue = ((Map<String, Object>) ti).get("issue");
                if (issue instanceof Map<?, ?> iss) {
                    issueId = stringFrom((Map<String, Object>) iss, "readableId");
                }
            }
        }

        if (issueId == null || issueId.isBlank()) {
            log.warn("task_update: no issue_id found — skipping tracker update");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("issue_id", "");
            out.put("pr_url", "");
            out.put("status_updated", false);
            out.put("comment_posted", false);
            out.put("skipped", true);
            out.put("skip_reason", "no issue_id");
            return out;
        }

        // --- Resolve provider ---
        // Resolution order:
        // 1. Explicit config (e.g. provider: youtrack in YAML block config)
        // 2. task_input block output .provider (set by YouTrackInputBlock etc.)
        // 3. tracker_provider run input (avoids collision with LLM 'provider' key)
        // Never read top-level input['provider'] — that carries the LLM routing key.
        String provider = stringFrom(cfg, "provider");
        if (provider == null) {
            Object taskInputOutput = input.get("task_input");
            if (taskInputOutput instanceof Map<?, ?> ti) {
                provider = stringFrom((Map<String, Object>) ti, "provider");
            }
        }
        if (provider == null) provider = stringFrom(input, "tracker_provider");
        if (provider == null) provider = "youtrack";

        // --- Detect mode ---
        boolean verdictMode = "verdict".equalsIgnoreCase(stringFrom(cfg, "mode"));

        // --- Resolve tracker config (injected by PipelineRunner as _<provider>_config) ---
        Map<String, Object> trackerConfig = resolveTrackerConfig(cfg, provider);
        TaskTracker tracker = trackerRegistry.get(provider);

        // --- Build comment + target status ---
        String comment;
        String targetStatus;
        String prUrl = null;
        String branch = null;

        String explicitComment = stringFrom(cfg, "comment");
        if (explicitComment != null) {
            // Explicit comment provided in YAML (already interpolated by StringInterpolator)
            comment = explicitComment;
            targetStatus = stringFrom(cfg, "status");
            if (targetStatus == null || targetStatus.isBlank()) targetStatus = "In Review";
        } else if (verdictMode) {
            boolean passed = resolveVerdict(input);
            comment = buildVerdictComment(input, passed);
            String statusPassed = stringFrom(cfg, "status_passed");
            String statusFailed = stringFrom(cfg, "status_failed");
            targetStatus = passed
                ? (statusPassed != null ? statusPassed : "Done")
                : (statusFailed != null ? statusFailed : "In Progress");
        } else {
            prUrl = extractPrUrl(input);
            branch = extractBranch(input);
            comment = buildHandoffComment(prUrl, branch, run);
            targetStatus = stringFrom(cfg, "status");
            if (targetStatus == null || targetStatus.isBlank()) targetStatus = "In Review";
        }

        // --- Write to tracker ---
        boolean commentPosted = false;
        boolean statusUpdated = false;

        try {
            tracker.addComment(issueId, comment, trackerConfig);
            commentPosted = true;
            log.info("task_update: posted handoff comment to {} {}", provider, issueId);
        } catch (Exception e) {
            log.warn("task_update: failed to post comment to {} {}: {}", provider, issueId, e.getMessage());
        }

        try {
            tracker.updateStatus(issueId, targetStatus, trackerConfig);
            statusUpdated = true;
            log.info("task_update: updated status of {} {} to '{}'", provider, issueId, targetStatus);
        } catch (Exception e) {
            log.warn("task_update: failed to update status of {} {}: {}", provider, issueId, e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issue_id", issueId);
        out.put("pr_url", prUrl != null ? prUrl : "");
        out.put("branch", branch != null ? branch : "");
        out.put("status_updated", statusUpdated);
        out.put("comment_posted", commentPosted);
        out.put("target_status", targetStatus);
        return out;
    }

    private String extractPrUrl(Map<String, Object> input) {
        // Try publish.final_text first, then any string in input
        String text = nestedString(input, "publish", "final_text");
        if (text == null) text = flatSearch(input, "final_text");
        if (text == null) return null;
        Matcher m = PR_URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    private String extractBranch(Map<String, Object> input) {
        String text = nestedString(input, "publish", "final_text");
        if (text == null) text = nestedString(input, "commit", "final_text");
        if (text == null) return null;
        Matcher m = BRANCH_PATTERN.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    @SuppressWarnings("unchecked")
    private boolean resolveVerdict(Map<String, Object> input) {
        // adversarial block is the verdict source in intent-verify
        Object adv = input.get("adversarial");
        if (adv instanceof Map<?, ?> m) {
            Object passed = ((Map<String, Object>) m).get("passed");
            if (passed instanceof Boolean b) return b;
            if (passed instanceof String s) return Boolean.parseBoolean(s);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String buildVerdictComment(Map<String, Object> input, boolean passed) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Intent Verification Verdict: ").append(passed ? "✅ PASSED" : "❌ FAILED").append("\n\n");

        Object adv = input.get("adversarial");
        if (adv instanceof Map<?, ?> m) {
            Map<String, Object> advMap = (Map<String, Object>) m;

            Object issues = advMap.get("issues");
            if (issues instanceof String s && !s.isBlank()) {
                sb.append("### Issues\n").append(s).append("\n\n");
            }

            Object checklist = advMap.get("checklist_status");
            if (checklist instanceof List<?> cs && !cs.isEmpty()) {
                sb.append("### Checklist\n");
                for (Object item : cs) {
                    if (item instanceof Map<?, ?> ci) {
                        Map<String, Object> ciMap = (Map<String, Object>) ci;
                        boolean itemPassed = Boolean.TRUE.equals(ciMap.get("passed"));
                        String id = String.valueOf(ciMap.getOrDefault("id", "?"));
                        String text = String.valueOf(ciMap.getOrDefault("text", ciMap.getOrDefault("evidence", "")));
                        sb.append(itemPassed ? "- ✅ " : "- ❌ ").append(id).append(": ").append(text).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        Object tests = input.get("run_tests");
        if (tests instanceof Map<?, ?> t) {
            Map<String, Object> tm = (Map<String, Object>) t;
            Boolean testSuccess = tm.get("success") instanceof Boolean b ? b : null;
            if (testSuccess != null) {
                sb.append("### Tests: ").append(testSuccess ? "✅ passed" : "❌ failed").append("\n");
            }
        }

        if (!passed) {
            sb.append("\nДля исправления: запустите `agent-sdlc` с тем же intent'ом, передав этот вердикт как контекст.");
        }
        return sb.toString();
    }

    private String buildHandoffComment(String prUrl, String branch, PipelineRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Agent-SDLC Handoff\n\n");
        if (branch != null && !branch.isBlank()) {
            sb.append("**Branch:** `").append(branch).append("`\n");
        }
        if (prUrl != null && !prUrl.isBlank()) {
            sb.append("**PR/MR:** ").append(prUrl).append("\n");
        }
        sb.append("\nГотово к ревью. Для проверки фичи запустите `intent-verify` с этой задачей.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String nestedString(Map<String, Object> input, String block, String field) {
        Object blockOut = input.get(block);
        if (blockOut instanceof Map<?, ?> m) {
            return stringFrom((Map<String, Object>) m, field);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String flatSearch(Map<String, Object> input, String field) {
        for (Object v : input.values()) {
            if (v instanceof Map<?, ?> m) {
                String s = stringFrom((Map<String, Object>) m, field);
                if (s != null && !s.isBlank()) return s;
            }
        }
        return null;
    }

    private String stringFrom(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof String s && !s.isBlank()) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveTrackerConfig(Map<String, Object> cfg, String provider) {
        Object snapshot = cfg.get("_" + provider + "_config");
        if (snapshot instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Object issuesSnapshot = cfg.get("_" + provider + "_issues_config");
        if (issuesSnapshot instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return cfg;
    }
}
