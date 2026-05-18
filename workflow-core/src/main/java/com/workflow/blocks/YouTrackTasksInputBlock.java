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

@Component
public class YouTrackTasksInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(YouTrackTasksInputBlock.class);

    @Autowired
    private TaskTrackerRegistry trackerRegistry;

    @Override
    public String getName() { return "youtrack_tasks_input"; }

    @Override
    public String getDescription() {
        return "Читает существующие дочерние задачи из родительской задачи трекера и формирует такой же вывод как task_creation.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();
        String provider = resolveProvider(cfg);

        // Resolve parent_issue_id
        String parentIssueId = null;
        Object cfgParentId = cfg.get("parent_issue_id");
        if (cfgParentId instanceof String s && !s.isBlank()) {
            parentIssueId = s;
        }

        if (parentIssueId == null) {
            // Try youtrack_input output (backward compat)
            Object ytInputObj = input.get("youtrack_input");
            if (ytInputObj instanceof Map<?, ?> ytInput) {
                Object sourceIssueObj = ((Map<String, Object>) ytInput).get("youtrack_source_issue");
                if (sourceIssueObj instanceof Map<?, ?> sourceIssue) {
                    parentIssueId = (String) ((Map<String, Object>) sourceIssue).get("id");
                }
            }
            // Also try task_input output
            if (parentIssueId == null) {
                Object taskInputObj = input.get("task_input");
                if (taskInputObj instanceof Map<?, ?> taskInput) {
                    Object issueObj = ((Map<String, Object>) taskInput).get("issue");
                    if (issueObj instanceof Map<?, ?> issueMap) {
                        Object readableId = ((Map<String, Object>) issueMap).get("readableId");
                        if (readableId instanceof String s && !s.isBlank()) parentIssueId = s;
                    }
                }
            }
        }

        if (parentIssueId == null || parentIssueId.isBlank()) {
            log.warn("No parent_issue_id found for YouTrackTasksInputBlock, returning empty tasks");
            return Map.of("tasks", new ArrayList<>(), "youtrack_issues", new ArrayList<>());
        }

        Map<String, Object> trackerConfig = resolveTrackerConfig(cfg, provider);
        List<TaskIssue> subtasks = trackerRegistry.get(provider).listSubtasks(parentIssueId, trackerConfig);

        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> youtrackIssues = new ArrayList<>();

        for (TaskIssue subtask : subtasks) {
            Map<String, Object> task = new HashMap<>();
            task.put("summary", subtask.summary());
            task.put("description", subtask.description() != null ? subtask.description() : "");
            task.put("type", "Task");
            task.put("priority", "Normal");
            task.put("estimated_hours", 0);
            tasks.add(task);

            Map<String, Object> issueRef = new HashMap<>();
            issueRef.put("id", subtask.readableId());
            issueRef.put("url", subtask.url());
            issueRef.put("summary", subtask.summary());
            youtrackIssues.add(issueRef);
        }

        log.info("Loaded {} subtasks from {} issue {}", subtasks.size(), provider, parentIssueId);

        return Map.of("tasks", tasks, "youtrack_issues", youtrackIssues);
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
}
