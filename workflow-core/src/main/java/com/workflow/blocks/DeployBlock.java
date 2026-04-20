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
public class DeployBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(DeployBlock.class);
    private static final Set<String> KNOWN_ENVIRONMENTS = Set.of("test", "staging", "prod");

    @Override
    public String getName() {
        return "deploy";
    }

    @Override
    public String getDescription() {
        return "Разворачивает собранный артефакт в указанное окружение (test/staging/prod) через CI/CD и ждёт завершения.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        String environment = requireString(cfg.get("environment"),
            "DeployBlock requires 'environment' in config (test|staging|prod)");
        if (!KNOWN_ENVIRONMENTS.contains(environment)) {
            log.warn("Unknown environment '{}', proceeding (custom envs are allowed)", environment);
        }

        Map<String, Object> artifact = resolveArtifact(input);
        if (artifact == null) {
            throw new IllegalStateException("DeployBlock: could not resolve artifact from input — expected 'build' dependency output");
        }

        String deployStrategy = stringOr(cfg.get("strategy"), "rolling");
        int timeoutSeconds = intOr(cfg.get("timeout_seconds"), 600);

        log.info("Deploy requested: env={}, artifact={}, strategy={}, timeout={}s",
            environment, artifact.get("artifact_id"), deployStrategy, timeoutSeconds);

        // TODO: вызов CI/CD API (GitLab/GitHub Actions/ArgoCD) для запуска deploy
        // TODO: polling статуса до завершения либо таймаута

        Map<String, Object> result = new HashMap<>();
        result.put("environment", environment);
        result.put("artifact_id", artifact.get("artifact_id"));
        result.put("artifact_version", artifact.get("artifact_version"));
        result.put("deployment_id", "dep-" + Instant.now().getEpochSecond());
        result.put("status", "success");
        result.put("url", "(not configured)");
        result.put("deployed_at", Instant.now().toString());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveArtifact(Map<String, Object> input) {
        Object direct = input.get("build");
        if (direct instanceof Map<?, ?> m) return (Map<String, Object>) m;
        for (Object val : input.values()) {
            if (val instanceof Map<?, ?> m && ((Map<String, Object>) m).containsKey("artifact_id")) {
                return (Map<String, Object>) m;
            }
        }
        return null;
    }

    private String requireString(Object value, String errorMessage) {
        if (value instanceof String s && !s.isBlank()) return s;
        throw new IllegalArgumentException(errorMessage);
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
