package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class VcsMergeBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(VcsMergeBlock.class);
    private static final Set<String> VALID_STRATEGIES = Set.of("merge", "squash", "rebase");

    @Override
    public String getName() {
        return "vcs_merge";
    }

    @Override
    public String getDescription() {
        return "Мержит approved MR/PR в целевую ветку (merge/squash/rebase), опционально удаляет source-ветку.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        String strategy = stringOr(cfg.get("strategy"), "merge");
        if (!VALID_STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("VcsMergeBlock: invalid strategy '" + strategy
                + "' (expected merge|squash|rebase)");
        }
        boolean deleteBranchAfter = boolOr(cfg.get("delete_branch_after"), true);

        Map<String, Object> mrData = resolveMr(input);
        if (mrData == null) {
            throw new IllegalStateException("VcsMergeBlock: could not resolve MR/PR from input — expected vcs_mr dependency");
        }

        log.info("Merge requested: provider={}, mr_id={}, strategy={}, delete_branch={}",
            mrData.get("provider"), mrData.get("mr_id"), strategy, deleteBranchAfter);

        // TODO: VcsProvider.merge(mrId, strategy, deleteBranchAfter)
        //
        // При merge-конфликте реализация должна вернуть:
        //   status: "conflict"
        //   conflicts: [ { file, context, ours, theirs }, ... ]
        //   issues: [ "Conflict in <file>: <reason>", ... ]
        //   main_diff: "<diff main относительно base ветки MR>"
        //
        // Это автоматически триггерит on_failure.loopback с failed_statuses: [conflict]
        // → code_generation получит фидбэк и перегенерирует код поверх актуальной main.

        Map<String, Object> result = new HashMap<>();
        result.put("provider", mrData.get("provider"));
        result.put("mr_id", mrData.get("mr_id"));
        result.put("merge_sha", "0000000");  // placeholder
        result.put("merged_branch", mrData.get("source_branch"));
        result.put("target_branch", mrData.get("target_branch"));
        result.put("strategy", strategy);
        result.put("status", "merged");
        result.put("conflicts", new java.util.ArrayList<>());
        result.put("issues", new java.util.ArrayList<>());
        result.put("merged_at", Instant.now().toString());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveMr(Map<String, Object> input) {
        for (String key : new String[]{"vcs_mr", "gitlab_mr", "github_pr", "mr"}) {
            Object val = input.get(key);
            if (val instanceof Map<?, ?> m) return (Map<String, Object>) m;
        }
        return null;
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private boolean boolOr(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}
