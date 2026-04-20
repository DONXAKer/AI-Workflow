package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.tracker.TaskIssue;
import com.workflow.integrations.tracker.TaskTracker;
import com.workflow.integrations.tracker.TaskTrackerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-agnostic replacement for {@code youtrack_input}. Resolves the tracker adapter
 * from {@code config.provider} (defaulting to {@code youtrack} for backward compatibility)
 * and loads a single issue by ID. Output shape:
 * <pre>
 * {
 *   "requirement": "# summary\n\ndescription\n\nIssue: PROJ-42 (url)",
 *   "issue": { id, readableId, summary, description, status, url, customFields },
 *   "provider": "youtrack"
 * }
 * </pre>
 *
 * <p>YAML:
 * <pre>
 * - id: fetch_issue
 *   block: task_input
 *   config:
 *     provider: youtrack    # or jira, linear
 *     issue_id: PROJ-42     # optional — falls back to input.issueId
 * </pre>
 */
@Component
public class TaskInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(TaskInputBlock.class);

    @Autowired
    private TaskTrackerRegistry trackerRegistry;

    @Override
    public String getName() {
        return "task_input";
    }

    @Override
    public String getDescription() {
        return "Загружает задачу из task tracker (YouTrack/Jira/Linear) через единый TaskTracker-адаптер и превращает её в строку требования.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();
        String provider = stringOr(cfg.get("provider"), "youtrack");
        String issueId = resolveIssueId(cfg, input);

        if (issueId == null || issueId.isBlank()) {
            log.info("TaskInputBlock: no issue ID — passing requirement through");
            Map<String, Object> result = new HashMap<>();
            result.put("requirement", input.getOrDefault("requirement", ""));
            result.put("issue", Map.of());
            result.put("provider", provider);
            return result;
        }

        Map<String, Object> trackerConfig = resolveTrackerConfig(cfg, provider);

        TaskTracker tracker = trackerRegistry.get(provider);
        TaskIssue issue = tracker.fetchIssue(issueId, trackerConfig);

        String requirement = buildRequirement(issue);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requirement", requirement);
        result.put("issue", issueToMap(issue));
        result.put("provider", provider);
        return result;
    }

    private String resolveIssueId(Map<String, Object> cfg, Map<String, Object> input) {
        String fromConfig = stringOr(cfg.get("issue_id"), null);
        if (fromConfig != null) return fromConfig;
        for (String key : new String[]{"issueId", "youtrack_issue_id", "youtrackIssue"}) {
            Object val = input.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        Object req = input.get("requirement");
        if (req instanceof String s && s.matches("^[A-Z]+-\\d+$")) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveTrackerConfig(Map<String, Object> cfg, String provider) {
        // Per-provider snapshots injected by PipelineRunner live under keys like _youtrack_config.
        Object snapshot = cfg.get("_" + provider + "_config");
        if (snapshot instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return cfg;
    }

    private String buildRequirement(TaskIssue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(issue.summary()).append("\n\n");
        if (issue.description() != null && !issue.description().isBlank()) {
            sb.append(issue.description()).append("\n\n");
        }
        if (issue.url() != null && !issue.url().isBlank()) {
            sb.append("Issue: ").append(issue.readableId()).append(" (").append(issue.url()).append(")");
        } else {
            sb.append("Issue: ").append(issue.readableId());
        }
        return sb.toString();
    }

    private Map<String, Object> issueToMap(TaskIssue issue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", issue.id());
        m.put("readableId", issue.readableId());
        m.put("summary", issue.summary());
        m.put("description", issue.description());
        m.put("status", issue.status());
        m.put("url", issue.url());
        m.put("customFields", issue.customFields());
        return m;
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }
}
