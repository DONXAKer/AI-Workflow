package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class VerifyProdBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(VerifyProdBlock.class);

    @Override
    public String getName() {
        return "verify_prod";
    }

    @Override
    public String getDescription() {
        return "Пост-деплой проверки прода: health checks, ключевые метрики, отсутствие всплеска ошибок. Триггер для rollback при провале.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        List<Map<String, Object>> checks = cfg.get("checks") instanceof List<?> l
            ? (List<Map<String, Object>>) l : List.of();
        int observationWindowSeconds = intOr(cfg.get("observation_window_seconds"), 120);

        log.info("Verify prod: {} check(s) over {}s observation window", checks.size(), observationWindowSeconds);

        // TODO: выполнить каждый check (HTTP health, Prometheus query, log scan)
        // TODO: сравнить метрики с baseline (pre-deploy snapshot)

        List<Map<String, Object>> results = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        boolean passed = true;

        for (Map<String, Object> check : checks) {
            String name = String.valueOf(check.getOrDefault("name", "unnamed"));
            Map<String, Object> checkResult = new HashMap<>();
            checkResult.put("name", name);
            checkResult.put("type", check.get("type"));
            checkResult.put("status", "pass");  // placeholder
            results.add(checkResult);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("passed", passed);
        result.put("checks", results);
        result.put("issues", issues);
        result.put("observed_at", Instant.now().toString());
        return result;
    }

    private int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        return fallback;
    }
}
