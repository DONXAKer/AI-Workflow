package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.vcs.VcsProvider;
import com.workflow.integrations.vcs.VcsProvider.MergeResult;
import com.workflow.integrations.vcs.VcsProvider.MergeStrategy;
import com.workflow.integrations.vcs.VcsProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class VcsMergeBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(VcsMergeBlock.class);
    private static final Set<String> VALID_STRATEGIES = Set.of("merge", "squash", "rebase");

    @Autowired(required = false)
    private VcsProviderRegistry vcsProviderRegistry;

    @Override
    public String getName() {
        return "vcs_merge";
    }

    @Override
    public String getDescription() {
        return "Мержит approved MR/PR в целевую ветку (merge/squash/rebase), опционально удаляет source-ветку. "
            + "При конфликте возвращает status=conflict + issues — триггерит loopback на codegen для регенерации поверх свежей main.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig() != null ? config.getConfig() : Map.of();

        String strategyName = stringOr(cfg.get("strategy"), "merge");
        if (!VALID_STRATEGIES.contains(strategyName)) {
            throw new IllegalArgumentException("VcsMergeBlock: invalid strategy '" + strategyName
                + "' (expected merge|squash|rebase)");
        }
        MergeStrategy strategy = MergeStrategy.valueOf(strategyName.toUpperCase());
        boolean deleteBranchAfter = boolOr(cfg.get("delete_branch_after"), true);

        Map<String, Object> mrData = resolveMr(input);
        if (mrData == null) {
            throw new IllegalStateException("VcsMergeBlock: could not resolve MR/PR from input — expected vcs_mr / gitlab_mr / github_pr dependency");
        }

        String providerName = stringOr(mrData.get("provider"),
            cfg.get("_github_config") != null ? "github" : "gitlab");
        long mrId = toLong(mrData.get("mr_id"));

        log.info("Merge requested: provider={}, mr_id={}, strategy={}, delete_branch={}",
            providerName, mrId, strategy, deleteBranchAfter);

        // Provider config comes from the upstream gitlab_mr / github_pr block via injected
        // _gitlab_config / _github_config keys (same convention as GitLabMRBlock).
        Map<String, Object> providerConfig = resolveProviderConfig(cfg, providerName);
        if (providerConfig == null || providerConfig.isEmpty()) {
            log.warn("VcsMergeBlock: no _{}_config in block config — returning placeholder result",
                providerName);
            return placeholderResult(providerName, mrId, mrData, strategyName);
        }

        if (vcsProviderRegistry == null) {
            log.warn("VcsMergeBlock: VcsProviderRegistry not wired — returning placeholder result");
            return placeholderResult(providerName, mrId, mrData, strategyName);
        }

        VcsProvider provider;
        try {
            provider = vcsProviderRegistry.get(providerName);
        } catch (Exception e) {
            log.error("VcsMergeBlock: unknown VCS provider '{}': {}", providerName, e.getMessage());
            return errorResult(providerName, mrId, mrData, strategyName,
                "Unknown VCS provider: " + providerName);
        }

        MergeResult mr;
        try {
            mr = provider.merge(mrId, strategy, deleteBranchAfter, providerConfig);
        } catch (Exception e) {
            log.error("VcsMergeBlock: merge failed for {}#{}: {}", providerName, mrId, e.getMessage());
            return errorResult(providerName, mrId, mrData, strategyName, e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("provider", providerName);
        result.put("mr_id", mrId);
        result.put("merge_sha", mr.mergeSha() != null ? mr.mergeSha() : "0000000");
        result.put("merged_branch", mrData.get("source_branch"));
        result.put("target_branch", mrData.get("target_branch"));
        result.put("strategy", strategyName);
        result.put("status", mr.status());
        result.put("conflicts", mr.conflicts() != null ? mr.conflicts() : new ArrayList<>());
        List<String> issues = new ArrayList<>();
        if ("conflict".equals(mr.status()) && mr.conflicts() != null) {
            for (String c : mr.conflicts()) issues.add("Conflict: " + c);
        }
        result.put("issues", issues);
        result.put("success", "merged".equals(mr.status()));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveProviderConfig(Map<String, Object> cfg, String providerName) {
        String key = "_" + providerName + "_config";
        Object raw = cfg.get(key);
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }

    private Map<String, Object> placeholderResult(String provider, long mrId, Map<String, Object> mrData,
                                                    String strategy) {
        Map<String, Object> result = new HashMap<>();
        result.put("provider", provider);
        result.put("mr_id", mrId);
        result.put("merge_sha", "0000000");
        result.put("merged_branch", mrData.get("source_branch"));
        result.put("target_branch", mrData.get("target_branch"));
        result.put("strategy", strategy);
        result.put("status", "merged");
        result.put("conflicts", new ArrayList<>());
        result.put("issues", new ArrayList<>());
        result.put("success", true);
        result.put("placeholder", true);
        result.put("merged_at", Instant.now().toString());
        return result;
    }

    private Map<String, Object> errorResult(String provider, long mrId, Map<String, Object> mrData,
                                              String strategy, String reason) {
        Map<String, Object> result = new HashMap<>();
        result.put("provider", provider);
        result.put("mr_id", mrId);
        result.put("merge_sha", null);
        result.put("merged_branch", mrData.get("source_branch"));
        result.put("target_branch", mrData.get("target_branch"));
        result.put("strategy", strategy);
        result.put("status", "failed");
        result.put("conflicts", new ArrayList<>());
        result.put("issues", List.of(reason));
        result.put("success", false);
        result.put("error", reason);
        result.put("merged_at", Instant.now().toString());
        return result;
    }

    private static String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static boolean boolOr(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }
}
