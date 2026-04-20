package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.GitLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GitLabCIBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(GitLabCIBlock.class);

    @Override
    public String getName() {
        return "gitlab_ci";
    }

    @Override
    public String getDescription() {
        return "Отслеживает CI pipeline GitLab связанный с merge request, ждёт завершения и сообщает итоговый статус.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // Resolve MR data: look for "mr" key first, then check if input itself has mr_id
        Map<String, Object> mrData = null;
        Object mrObj = input.get("mr");
        if (mrObj instanceof Map) {
            mrData = (Map<String, Object>) mrObj;
        } else {
            // Try other common keys used by gitlab_mr and mr_input blocks
            for (String key : new String[]{"gitlab_mr", "mr_input"}) {
                Object val = input.get(key);
                if (val instanceof Map) {
                    mrData = (Map<String, Object>) val;
                    break;
                }
            }
        }
        if (mrData == null && input.containsKey("mr_id")) {
            mrData = input;
        }
        if (mrData == null) {
            mrData = new HashMap<>();
        }

        int mrIid = 0;
        Object mrIdObj = mrData.get("mr_id");
        if (mrIdObj instanceof Number) {
            mrIid = ((Number) mrIdObj).intValue();
        }

        if (mrIid == 0) {
            throw new IllegalArgumentException("GitLabCIBlock: could not resolve mr_id from input");
        }

        // Get GitLab config
        Object gitlabCfgObj = cfg.get("_gitlab_config");
        if (!(gitlabCfgObj instanceof Map)) {
            log.warn("No _gitlab_config found in GitLabCIBlock, returning placeholder");
            Map<String, Object> result = new HashMap<>();
            result.put("pipeline_id", 0);
            result.put("pipeline_url", "(gitlab not configured)");
            result.put("status", "unknown");
            result.put("stages", new HashMap<>());
            return result;
        }

        Map<String, Object> gitlabCfg = (Map<String, Object>) gitlabCfgObj;
        String baseUrl = (String) gitlabCfg.getOrDefault("base_url", "https://gitlab.com");
        String token = (String) gitlabCfg.getOrDefault("token", "");
        Object projectIdObj = gitlabCfg.get("project_id");
        int projectId = 0;
        if (projectIdObj instanceof Number) {
            projectId = ((Number) projectIdObj).intValue();
        } else if (projectIdObj instanceof String) {
            try { projectId = Integer.parseInt((String) projectIdObj); } catch (NumberFormatException ignored) {}
        }

        int timeoutSeconds = 600;
        Object timeoutObj = cfg.get("timeout_seconds");
        if (timeoutObj instanceof Number) {
            timeoutSeconds = ((Number) timeoutObj).intValue();
        }

        GitLabClient gitLabClient = new GitLabClient(baseUrl, token, projectId);

        // Get MR pipelines
        List<Map<String, Object>> pipelines = gitLabClient.getMrPipelines(mrIid);
        if (pipelines.isEmpty()) {
            log.warn("No pipelines found for MR #{}", mrIid);
            Map<String, Object> result = new HashMap<>();
            result.put("pipeline_id", 0);
            result.put("pipeline_url", "");
            result.put("status", "not_found");
            result.put("stages", new HashMap<>());
            return result;
        }

        // Take first (latest) pipeline
        Map<String, Object> latestPipeline = pipelines.get(0);
        int pipelineId = 0;
        if (latestPipeline.get("id") instanceof Number) {
            pipelineId = ((Number) latestPipeline.get("id")).intValue();
        }

        log.info("Waiting for GitLab CI pipeline {} (MR #{}, timeout={}s)", pipelineId, mrIid, timeoutSeconds);

        // Wait for pipeline to finish
        String finalStatus = gitLabClient.waitForPipeline(pipelineId, timeoutSeconds);

        // Get pipeline details for stage summary
        Map<String, Object> pipelineDetails = gitLabClient.getPipelineStatus(pipelineId);
        String pipelineUrl = (String) pipelineDetails.getOrDefault("web_url", "");

        // Build stage summary from pipeline details
        Map<String, Object> stages = extractStages(pipelineDetails);

        Map<String, Object> result = new HashMap<>();
        result.put("pipeline_id", pipelineId);
        result.put("pipeline_url", pipelineUrl);
        result.put("status", finalStatus);
        result.put("stages", stages);
        return result;
    }

    private Map<String, Object> extractStages(Map<String, Object> pipelineDetails) {
        Map<String, Object> stages = new HashMap<>();
        // The pipeline status endpoint doesn't return detailed stage breakdown,
        // but we can include the overall status and relevant fields
        stages.put("status", pipelineDetails.getOrDefault("status", "unknown"));
        stages.put("ref", pipelineDetails.getOrDefault("ref", ""));
        stages.put("sha", pipelineDetails.getOrDefault("sha", ""));
        stages.put("created_at", pipelineDetails.getOrDefault("created_at", ""));
        stages.put("finished_at", pipelineDetails.getOrDefault("finished_at", ""));
        stages.put("duration", pipelineDetails.getOrDefault("duration", 0));
        return stages;
    }
}
