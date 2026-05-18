package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.tracker.TaskIssue;
import com.workflow.integrations.tracker.TaskTrackerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Provider-agnostic replacement for {@code youtrack_tasks_input}. Lists direct subtasks
 * of a parent issue via the {@link TaskTrackerRegistry}.
 *
 * <p>YAML:
 * <pre>
 * - id: list_subtasks
 *   block: task_subtasks_input
 *   config:
 *     provider: youtrack    # or jira, github, gitlab
 *     parent_issue_id: PROJ-42    # optional — falls back to upstream task_input/youtrack_input
 * </pre>
 *
 * <p>Output:
 * <pre>
 * {
 *   "subtasks": [{ "id": "PROJ-43", "summary": "...", "status": "...", "url": "..." }],
 *   "issues":   [...]   // alias — same content
 * }
 * </pre>
 */
@Component
public class TaskSubtasksInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(TaskSubtasksInputBlock.class);

    @Autowired
    private TaskTrackerRegistry trackerRegistry;

    @Override
    public String getName() { return "task_subtasks_input"; }

    @Override
    public String getDescription() {
        return "Читает список дочерних задач из task tracker (YouTrack/Jira/GitHub/GitLab) через единый адаптер.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();
        String provider = resolveProvider(cfg);

        String parentIssueId = resolveParentId(cfg, input);
        if (parentIssueId == null || parentIssueId.isBlank()) {
            log.warn("task_subtasks_input: no parent_issue_id resolved — returning empty");
            return Map.of("subtasks", new ArrayList<>(), "issues", new ArrayList<>());
        }

        Map<String, Object> trackerConfig = resolveTrackerConfig(cfg, provider);
        List<TaskIssue> subtasks = trackerRegistry.get(provider).listSubtasks(parentIssueId, trackerConfig);

        List<Map<String, Object>> result = new ArrayList<>(subtasks.size());
        for (TaskIssue issue : subtasks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", issue.readableId());
            item.put("summary", issue.summary());
            item.put("status", issue.status());
            item.put("url", issue.url());
            result.add(item);
        }

        log.info("task_subtasks_input: loaded {} subtasks from {} issue {}", result.size(), provider, parentIssueId);
        return Map.of("subtasks", result, "issues", result);
    }

    @SuppressWarnings("unchecked")
    private String resolveParentId(Map<String, Object> cfg, Map<String, Object> input) {
        // 1. Explicit config
        Object cfgId = cfg.get("parent_issue_id");
        if (cfgId instanceof String s && !s.isBlank()) return s;

        // 2. Upstream task_input output
        Object taskInputObj = input.get("task_input");
        if (taskInputObj instanceof Map<?, ?> taskInput) {
            Object issueObj = ((Map<String, Object>) taskInput).get("issue");
            if (issueObj instanceof Map<?, ?> issueMap) {
                Object readableId = ((Map<String, Object>) issueMap).get("readableId");
                if (readableId instanceof String s && !s.isBlank()) return s;
            }
        }

        // 3. Upstream youtrack_input output (backward compat)
        Object ytInputObj = input.get("youtrack_input");
        if (ytInputObj instanceof Map<?, ?> ytInput) {
            Object sourceIssueObj = ((Map<String, Object>) ytInput).get("youtrack_source_issue");
            if (sourceIssueObj instanceof Map<?, ?> sourceIssue) {
                Object id = ((Map<String, Object>) sourceIssue).get("id");
                if (id instanceof String s && !s.isBlank()) return s;
            }
        }

        return null;
    }

    private String resolveProvider(Map<String, Object> cfg) {
        Object explicit = cfg.get("provider");
        if (explicit instanceof String s && !s.isBlank()) return s;
        Object defaultProvider = cfg.get("_default_tracker_provider");
        if (defaultProvider instanceof String s && !s.isBlank()) return s;
        return "youtrack";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveTrackerConfig(Map<String, Object> cfg, String provider) {
        Object snapshot = cfg.get("_" + provider + "_config");
        if (snapshot instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Object issuesSnapshot = cfg.get("_" + provider + "_issues_config");
        if (issuesSnapshot instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return cfg;
    }

    private Map<String, Object> issueToMap(TaskIssue issue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", issue.readableId());
        m.put("summary", issue.summary());
        m.put("status", issue.status());
        m.put("url", issue.url());
        return m;
    }
}
