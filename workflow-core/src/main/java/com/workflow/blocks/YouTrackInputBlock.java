package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.YouTrackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class YouTrackInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(YouTrackInputBlock.class);

    @Override
    public String getName() {
        return "youtrack_input";
    }

    @Override
    public String getDescription() {
        return "Загружает задачу из YouTrack (название, описание, комментарии) и преобразует её в строку требования для последующих блоков.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // Resolve issue_id
        String issueId = null;
        Object cfgIssueId = cfg.get("issue_id");
        if (cfgIssueId instanceof String && !((String) cfgIssueId).isBlank()) {
            issueId = (String) cfgIssueId;
        } else if (input.get("youtrack_issue_id") instanceof String) {
            issueId = (String) input.get("youtrack_issue_id");
        }

        if (issueId == null || issueId.isBlank()) {
            // No issue ID - pass through requirement from input
            Map<String, Object> result = new HashMap<>();
            result.put("requirement", input.getOrDefault("requirement", ""));
            result.put("youtrack_source_issue", new HashMap<>());
            return result;
        }

        // Get YouTrack config
        Object ytCfgObj = cfg.get("_youtrack_config");
        if (!(ytCfgObj instanceof Map)) {
            log.warn("No _youtrack_config found in block config, returning fallback");
            Map<String, Object> result = new HashMap<>();
            result.put("requirement", input.getOrDefault("requirement", ""));
            result.put("youtrack_source_issue", new HashMap<>());
            return result;
        }
        Map<String, Object> ytCfg = (Map<String, Object>) ytCfgObj;

        String baseUrl = (String) ytCfg.getOrDefault("base_url", "");
        String token = (String) ytCfg.getOrDefault("token", "");
        String project = (String) ytCfg.getOrDefault("project", "");

        YouTrackClient client = new YouTrackClient(baseUrl, token, project);

        Map<String, Object> issue = client.getIssue(issueId);

        String summary = (String) issue.getOrDefault("summary", "");
        String description = (String) issue.getOrDefault("description", "");

        // Build requirement string
        StringBuilder requirementBuilder = new StringBuilder();
        requirementBuilder.append("# ").append(summary).append("\n\n");
        if (description != null && !description.isBlank()) {
            requirementBuilder.append(description).append("\n\n");
        }

        String issueReadableId = (String) issue.getOrDefault("idReadable", issueId);
        String issueUrl = baseUrl + "/issue/" + issueReadableId;

        // task_mode may be pre-set in config or injected via input (e.g. from the approval gate edit)
        String taskMode = "decompose";
        Object cfgMode = cfg.get("task_mode");
        if (cfgMode instanceof String && !((String) cfgMode).isBlank()) {
            taskMode = (String) cfgMode;
        } else if (input.get("task_mode") instanceof String) {
            taskMode = (String) input.get("task_mode");
        }

        Map<String, Object> sourceIssue = new HashMap<>();
        sourceIssue.put("id", issueReadableId);
        sourceIssue.put("url", issueUrl);
        sourceIssue.put("summary", summary);

        Map<String, Object> result = new HashMap<>();
        result.put("requirement", requirementBuilder.toString().trim());
        result.put("task_mode", taskMode);
        result.put("youtrack_source_issue", sourceIssue);
        return result;
    }
}
