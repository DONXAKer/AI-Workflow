package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class BuildBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(BuildBlock.class);

    @Override
    public String getName() {
        return "build";
    }

    @Override
    public String getDescription() {
        return "Собирает артефакт (docker image / jar) из merged ветки и регистрирует его в registry для последующего промоута по окружениям.";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        String buildSystem = stringOr(cfg.get("build_system"), "gitlab_ci");
        String ref = resolveRef(input, cfg);
        String artifactType = stringOr(cfg.get("artifact_type"), "docker");
        String registry = stringOr(cfg.get("registry"), "");

        String version = generateVersion(run);

        log.info("Build requested: system={}, ref={}, type={}, version={}", buildSystem, ref, artifactType, version);

        // TODO: вызвать CI/CD API для запуска build job и опросить статус
        // TODO: извлечь digest/tag из результата build

        Map<String, Object> result = new HashMap<>();
        result.put("artifact_id", artifactType + ":" + version);
        result.put("artifact_version", version);
        result.put("artifact_type", artifactType);
        result.put("registry", registry);
        result.put("ref", ref);
        result.put("status", "success");
        result.put("built_at", Instant.now().toString());
        return result;
    }

    @SuppressWarnings("unchecked")
    private String resolveRef(Map<String, Object> input, Map<String, Object> cfg) {
        Object refObj = cfg.get("ref");
        if (refObj instanceof String s && !s.isBlank()) return s;
        for (String key : new String[]{"vcs_merge", "merge", "gitlab_merge", "github_merge"}) {
            Object val = input.get(key);
            if (val instanceof Map<?, ?> m) {
                Object sha = ((Map<String, Object>) m).get("merge_sha");
                if (sha instanceof String s && !s.isBlank()) return s;
            }
        }
        return "main";
    }

    private String generateVersion(PipelineRun run) {
        String runPart = run.getId() != null ? run.getId().toString().substring(0, 8) : "local";
        return Instant.now().getEpochSecond() + "-" + runPart;
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }
}
