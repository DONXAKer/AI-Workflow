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
import java.util.List;
import java.util.Map;

@Component
public class YouTrackInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(YouTrackInputBlock.class);

    @Autowired
    private TaskTrackerRegistry trackerRegistry;

    @Override
    public String getName() { return "youtrack_input"; }

    @Override
    public String getDescription() {
        return "Загружает задачу из task tracker (YouTrack/Jira/GitLab/GitHub) и преобразует её в строку требования для последующих блоков.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Task input (legacy: youtrack_input)",
            "input",
            Phase.INTAKE,
            List.of(
                FieldSchema.string("issue_id", "ID задачи",
                    "Опциональный ID задачи (PROJ-42). Если пусто — берётся из run inputs."),
                FieldSchema.string("provider", "Провайдер трекера",
                    "youtrack / jira / github / gitlab (default: youtrack или настройка проекта)")
            ),
            false,
            Map.of()
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        String provider = resolveProvider(cfg);

        // Resolve issue_id
        String issueId = null;
        Object cfgIssueId = cfg.get("issue_id");
        if (cfgIssueId instanceof String s && !s.isBlank()) {
            issueId = s;
        } else if (input.get("youtrack_issue_id") instanceof String s) {
            issueId = s;
        } else if (input.get("issueId") instanceof String s) {
            issueId = s;
        }

        if (issueId == null || issueId.isBlank()) {
            Map<String, Object> result = new HashMap<>();
            result.put("requirement", input.getOrDefault("requirement", ""));
            result.put("youtrack_source_issue", new HashMap<>());
            result.put("issue", Map.of());
            result.put("provider", provider);
            return result;
        }

        Map<String, Object> trackerConfig = resolveTrackerConfig(cfg, provider);
        TaskTracker tracker = trackerRegistry.get(provider);
        TaskIssue issue = tracker.fetchIssue(issueId, trackerConfig);

        String taskMode = "decompose";
        Object cfgMode = cfg.get("task_mode");
        if (cfgMode instanceof String s && !s.isBlank()) {
            taskMode = s;
        } else if (input.get("task_mode") instanceof String s) {
            taskMode = s;
        }

        String requirement = buildRequirement(issue);

        // youtrack_source_issue preserved for backward compat with youtrack_tasks block
        Map<String, Object> sourceIssue = new HashMap<>();
        sourceIssue.put("id", issue.readableId());
        sourceIssue.put("url", issue.url());
        sourceIssue.put("summary", issue.summary());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requirement", requirement);
        result.put("task_mode", taskMode);
        result.put("youtrack_source_issue", sourceIssue);
        result.put("issue", issueToMap(issue));
        result.put("provider", provider);
        return result;
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
        // For github and gitlab, try issues-specific config key
        Object issuesSnapshot = cfg.get("_" + provider + "_issues_config");
        if (issuesSnapshot instanceof Map<?, ?> m) return (Map<String, Object>) m;
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
        return sb.toString().trim();
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
}
