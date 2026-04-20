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
public class RunTestsBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(RunTestsBlock.class);

    @Override
    public String getName() {
        return "run_tests";
    }

    @Override
    public String getDescription() {
        return "Запускает acceptance/smoke тесты против указанного окружения через CI/CD. Триггер для loopback на code_generation при падении.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        String testType = stringOr(cfg.get("type"), "smoke");
        String environment = resolveEnvironment(input, cfg);
        String suite = stringOr(cfg.get("suite"), "default");
        int timeoutSeconds = intOr(cfg.get("timeout_seconds"), 1200);

        log.info("Run tests: type={}, env={}, suite={}, timeout={}s", testType, environment, suite, timeoutSeconds);

        // TODO: вызов CI/CD API для запуска test job (например, pytest / jest / playwright)
        // TODO: парсинг отчёта (JUnit XML / Allure) → failed_tests list

        Map<String, Object> result = new HashMap<>();
        result.put("type", testType);
        result.put("environment", environment);
        result.put("suite", suite);
        result.put("tests_run", 0);
        result.put("tests_passed", 0);
        result.put("tests_failed", 0);
        result.put("failed_tests", new ArrayList<String>());
        result.put("status", "passed");
        result.put("report_url", "");
        result.put("finished_at", Instant.now().toString());
        return result;
    }

    @SuppressWarnings("unchecked")
    private String resolveEnvironment(Map<String, Object> input, Map<String, Object> cfg) {
        Object explicit = cfg.get("environment");
        if (explicit instanceof String s && !s.isBlank()) return s;
        for (Object val : input.values()) {
            if (val instanceof Map<?, ?> m) {
                Object env = ((Map<String, Object>) m).get("environment");
                if (env instanceof String s && !s.isBlank()) return s;
            }
        }
        return "test";
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        return fallback;
    }
}
