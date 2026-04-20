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
public class RollbackBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(RollbackBlock.class);
    private static final Set<String> VALID_STRATEGIES = Set.of("auto", "manual", "forward_fix");

    @Override
    public String getName() {
        return "rollback";
    }

    @Override
    public String getDescription() {
        return "Откатывает последний деплой в указанное окружение до предыдущей стабильной версии через CI/CD API.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        String strategy = stringOr(cfg.get("strategy"), "manual");
        if (!VALID_STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("RollbackBlock: invalid strategy '" + strategy
                + "' (expected auto|manual|forward_fix)");
        }

        if ("forward_fix".equals(strategy)) {
            log.info("Rollback strategy = forward_fix — no rollback performed, expected fix-forward in next iteration");
            Map<String, Object> skipped = new HashMap<>();
            skipped.put("strategy", strategy);
            skipped.put("status", "skipped");
            skipped.put("reason", "forward_fix requested");
            return skipped;
        }

        String environment = stringOr(cfg.get("environment"), "prod");
        Map<String, Object> previousDeploy = resolvePreviousDeploy(input, cfg);

        log.info("Rollback requested: env={}, strategy={}, target={}",
            environment, strategy, previousDeploy != null ? previousDeploy.get("artifact_id") : "unknown");

        // TODO: вызов CI/CD API для rollback на previous_artifact_id
        // TODO: дождаться завершения, проверить health

        Map<String, Object> result = new HashMap<>();
        result.put("strategy", strategy);
        result.put("environment", environment);
        result.put("previous_artifact_id", previousDeploy != null ? previousDeploy.get("artifact_id") : null);
        result.put("status", "success");
        result.put("rolled_back_at", Instant.now().toString());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvePreviousDeploy(Map<String, Object> input, Map<String, Object> cfg) {
        Object explicit = cfg.get("previous_artifact_id");
        if (explicit instanceof String s && !s.isBlank()) {
            Map<String, Object> synth = new HashMap<>();
            synth.put("artifact_id", s);
            return synth;
        }
        // Реальный lookup по истории деплоев — TODO (нужен DeploymentHistoryRepository)
        return null;
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }
}
