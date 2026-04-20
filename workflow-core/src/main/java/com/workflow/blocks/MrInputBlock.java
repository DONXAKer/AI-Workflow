package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.GitLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MrInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(MrInputBlock.class);

    @Override
    public String getName() {
        return "mr_input";
    }

    @Override
    public String getDescription() {
        return "Читает существующий GitLab Merge Request (по MR IID или ветке) и формирует такой же вывод как gitlab_mr.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // Resolve mr_iid from config
        Integer mrIid = null;
        Object mrIidObj = cfg.get("mr_iid");
        if (mrIidObj instanceof Number) {
            mrIid = ((Number) mrIidObj).intValue();
        } else if (mrIidObj instanceof String) {
            try { mrIid = Integer.parseInt((String) mrIidObj); } catch (NumberFormatException ignored) {}
        }

        String branch = (String) cfg.getOrDefault("branch", "");

        if (mrIid == null && (branch == null || branch.isBlank())) {
            throw new IllegalArgumentException("MrInputBlock requires 'mr_iid' or 'branch' in config");
        }

        // Get GitLab config
        Object gitlabCfgObj = cfg.get("_gitlab_config");
        if (!(gitlabCfgObj instanceof Map)) {
            log.warn("No _gitlab_config found in MrInputBlock");
            throw new RuntimeException("MrInputBlock requires _gitlab_config");
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

        GitLabClient gitLabClient = new GitLabClient(baseUrl, token, projectId);

        Map<String, Object> mr;
        if (mrIid != null) {
            mr = gitLabClient.getMergeRequest(mrIid);
        } else {
            mr = gitLabClient.getMergeRequestByBranch(branch);
            if (mr == null) {
                throw new RuntimeException("No open MR found for branch: " + branch);
            }
        }

        int resolvedMrId = 0;
        if (mr.get("iid") instanceof Number) resolvedMrId = ((Number) mr.get("iid")).intValue();
        String mrUrl = (String) mr.getOrDefault("web_url", "");
        String mrBranch = (String) mr.getOrDefault("source_branch", branch != null ? branch : "");
        String title = (String) mr.getOrDefault("title", "");

        Map<String, Object> result = new HashMap<>();
        result.put("mr_id", resolvedMrId);
        result.put("mr_url", mrUrl);
        result.put("branch", mrBranch);
        result.put("title", title);
        result.put("youtrack_issues_linked", new ArrayList<>());
        return result;
    }
}
