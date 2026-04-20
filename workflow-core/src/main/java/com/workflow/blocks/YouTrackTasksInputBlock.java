package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.YouTrackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class YouTrackTasksInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(YouTrackTasksInputBlock.class);

    @Override
    public String getName() {
        return "youtrack_tasks_input";
    }

    @Override
    public String getDescription() {
        return "Читает существующие дочерние задачи из родительской задачи YouTrack и формирует такой же вывод как youtrack_tasks.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // Resolve parent_issue_id from config or from youtrack_input output
        String parentIssueId = null;
        Object cfgParentId = cfg.get("parent_issue_id");
        if (cfgParentId instanceof String && !((String) cfgParentId).isBlank()) {
            parentIssueId = (String) cfgParentId;
        }

        if (parentIssueId == null) {
            // Try to get from youtrack_input output
            Object ytInputObj = input.get("youtrack_input");
            if (ytInputObj instanceof Map) {
                Map<String, Object> ytInput = (Map<String, Object>) ytInputObj;
                Object sourceIssueObj = ytInput.get("youtrack_source_issue");
                if (sourceIssueObj instanceof Map) {
                    Map<String, Object> sourceIssue = (Map<String, Object>) sourceIssueObj;
                    parentIssueId = (String) sourceIssue.get("id");
                }
            }
        }

        if (parentIssueId == null || parentIssueId.isBlank()) {
            log.warn("No parent_issue_id found for YouTrackTasksInputBlock, returning empty tasks");
            Map<String, Object> result = new HashMap<>();
            result.put("tasks", new ArrayList<>());
            result.put("youtrack_issues", new ArrayList<>());
            return result;
        }

        // Get YouTrack config
        Object ytCfgObj = cfg.get("_youtrack_config");
        if (!(ytCfgObj instanceof Map)) {
            log.warn("No _youtrack_config found, returning empty tasks");
            Map<String, Object> result = new HashMap<>();
            result.put("tasks", new ArrayList<>());
            result.put("youtrack_issues", new ArrayList<>());
            return result;
        }

        Map<String, Object> ytCfg = (Map<String, Object>) ytCfgObj;
        String baseUrl = (String) ytCfg.getOrDefault("base_url", "");
        String token = (String) ytCfg.getOrDefault("token", "");
        String project = (String) ytCfg.getOrDefault("project", "");

        YouTrackClient client = new YouTrackClient(baseUrl, token, project);

        List<Map<String, Object>> subtasks = client.getSubtasks(parentIssueId);

        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> youtrackIssues = new ArrayList<>();

        for (Map<String, Object> subtask : subtasks) {
            String issueId = (String) subtask.getOrDefault("idReadable", subtask.getOrDefault("id", ""));
            String summary = (String) subtask.getOrDefault("summary", "");
            String issueUrl = baseUrl + "/issue/" + issueId;

            // Build task entry
            Map<String, Object> task = new HashMap<>();
            task.put("summary", summary);
            task.put("description", "");
            task.put("type", "Task");
            task.put("priority", "Normal");
            task.put("estimated_hours", 0);
            tasks.add(task);

            // Build youtrack_issue entry
            Map<String, Object> issueRef = new HashMap<>();
            issueRef.put("id", issueId);
            issueRef.put("url", issueUrl);
            issueRef.put("summary", summary);
            youtrackIssues.add(issueRef);
        }

        log.info("Loaded {} subtasks from YouTrack issue {}", subtasks.size(), parentIssueId);

        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("youtrack_issues", youtrackIssues);
        return result;
    }
}
