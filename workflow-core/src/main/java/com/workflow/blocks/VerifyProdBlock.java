package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-deploy verification. Executes a list of {@code checks} (HTTP health probes for now —
 * Prometheus/log scans are a follow-up) and surfaces a single passed/failed verdict that
 * downstream blocks (typically {@code rollback}) can react to via {@code on_failure}.
 *
 * <p>Block config:
 * <pre>
 * - id: verify_prod
 *   block: verify_prod
 *   config:
 *     observation_window_seconds: 120    # informational; doesn't actually wait
 *     checks:
 *       - { name: api_health, type: http, url: "https://prod.example.com/health",
 *           expected_status: 200, expected_body_contains: "ok" }
 *       - { name: index_page, type: http, url: "https://prod.example.com/", expected_status: 200 }
 *   on_failure:
 *     action: loopback
 *     target: rollback
 * </pre>
 */
@Component
public class VerifyProdBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(VerifyProdBlock.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @Autowired(required = false) private StringInterpolator stringInterpolator;

    @Override
    public String getName() {
        return "verify_prod";
    }

    @Override
    public String getDescription() {
        return "Пост-деплой проверки: выполняет HTTP health-checks по списку 'checks'. "
            + "Каждый check — {name, type: http, url, expected_status, expected_body_contains}. "
            + "Возвращает passed=false + issues при провале — триггерит rollback через on_failure.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig() != null ? config.getConfig() : Map.of();

        List<Map<String, Object>> checks = cfg.get("checks") instanceof List<?> l
            ? (List<Map<String, Object>>) l : List.of();
        int observationWindowSeconds = intOr(cfg.get("observation_window_seconds"), 0);
        int requestTimeoutSeconds = intOr(cfg.get("request_timeout_seconds"), 15);

        log.info("Verify prod: {} check(s) over {}s window", checks.size(), observationWindowSeconds);

        if (observationWindowSeconds > 0) {
            // Brief settle delay between deploy and checks so freshly-rolled pods stabilize.
            try { Thread.sleep(Math.min(observationWindowSeconds, 300) * 1000L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        boolean passed = true;

        for (Map<String, Object> check : checks) {
            String name = String.valueOf(check.getOrDefault("name", "unnamed"));
            String type = String.valueOf(check.getOrDefault("type", "http"));

            Map<String, Object> checkResult = new HashMap<>();
            checkResult.put("name", name);
            checkResult.put("type", type);

            try {
                if ("http".equalsIgnoreCase(type)) {
                    boolean ok = runHttpCheck(check, run, input, requestTimeoutSeconds, checkResult);
                    if (!ok) {
                        passed = false;
                        issues.add(name + ": " + checkResult.getOrDefault("error", "failed"));
                    }
                } else {
                    // Unknown check type — surface as a soft pass to avoid breaking pipelines on a typo;
                    // operator sees the warning in the result.
                    checkResult.put("status", "skipped");
                    checkResult.put("warning", "unknown check type: " + type);
                    log.warn("Verify prod: unknown check type '{}' for '{}', skipping", type, name);
                }
            } catch (Exception e) {
                passed = false;
                checkResult.put("status", "error");
                checkResult.put("error", e.getMessage());
                issues.add(name + ": " + e.getMessage());
            }
            results.add(checkResult);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("passed", passed);
        result.put("status", passed ? "passed" : "failed");
        result.put("success", passed);
        result.put("checks", results);
        result.put("issues", issues);
        result.put("observed_at", Instant.now().toString());
        return result;
    }

    private boolean runHttpCheck(Map<String, Object> check, PipelineRun run, Map<String, Object> input,
                                   int timeoutSec, Map<String, Object> checkResult) throws Exception {
        String url = String.valueOf(check.get("url"));
        if (url == null || url.isBlank()) {
            checkResult.put("status", "error");
            checkResult.put("error", "missing 'url'");
            return false;
        }
        String resolvedUrl = stringInterpolator != null
            ? stringInterpolator.interpolate(url, run, input) : url;
        int expectedStatus = intOr(check.get("expected_status"), 200);
        String expectBody = check.get("expected_body_contains") instanceof String s ? s : null;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(resolvedUrl))
            .timeout(Duration.ofSeconds(timeoutSec))
            .GET()
            .build();

        Instant started = Instant.now();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            checkResult.put("status", "error");
            checkResult.put("url", resolvedUrl);
            checkResult.put("error", "connect failed: " + e.getMessage());
            return false;
        }
        long elapsed = Instant.now().toEpochMilli() - started.toEpochMilli();

        checkResult.put("url", resolvedUrl);
        checkResult.put("http_status", response.statusCode());
        checkResult.put("duration_ms", elapsed);

        if (response.statusCode() != expectedStatus) {
            checkResult.put("status", "fail");
            checkResult.put("error", "HTTP " + response.statusCode() + " (expected " + expectedStatus + ")");
            return false;
        }
        if (expectBody != null && !response.body().contains(expectBody)) {
            String preview = response.body().length() > 200
                ? response.body().substring(0, 200) + "..." : response.body();
            checkResult.put("status", "fail");
            checkResult.put("error", "body does not contain '" + expectBody + "', got: " + preview);
            return false;
        }
        checkResult.put("status", "pass");
        return true;
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
