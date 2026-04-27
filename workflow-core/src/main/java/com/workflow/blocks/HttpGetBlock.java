package com.workflow.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches a URL via HTTP GET and exposes the status/body/headers for downstream blocks.
 * Useful for pinging webhooks, checking external health endpoints, or pulling JSON from
 * an API without writing a dedicated integration.
 *
 * <p>YAML:
 * <pre>
 * - id: check_prod
 *   block: http_get
 *   config:
 *     url: https://api.example.com/health
 *     headers:
 *       Authorization: "Bearer ${input.token}"
 *     timeout_sec: 10            # default 30
 *     parse_json: true           # default false — if true, body is parsed into `json`
 *     allow_nonzero_status: true # default false — non-2xx raises instead of returning
 * </pre>
 *
 * <p>Output: {@code url}, {@code status}, {@code success}, {@code body}, {@code headers}
 * (Map&lt;String,String&gt; — first value per header), {@code duration_ms}.
 * When {@code parse_json: true} and the body parses cleanly, {@code json} is a
 * {@link com.fasterxml.jackson.databind.JsonNode} exposed as a generic {@code Object}
 * so callers can navigate via PathResolver.
 */
@Component
public class HttpGetBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(HttpGetBlock.class);
    private static final int DEFAULT_TIMEOUT_SEC = 30;
    private static final int MAX_BODY_BYTES = 512 * 1024;

    @Autowired private WebClient.Builder webClientBuilder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private StringInterpolator stringInterpolator;

    @Override public String getName() { return "http_get"; }

    @Override public String getDescription() {
        return "Выполняет HTTP GET на настроенный URL. Передаёт статус, тело и заголовки ответа следующим блокам. Опциональный парсинг JSON.";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        String rawUrl = asRequiredString(cfg, "url");
        String url = interpolate(rawUrl, run, input);
        int timeoutSec = asInt(cfg, "timeout_sec", DEFAULT_TIMEOUT_SEC);
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;
        boolean parseJson = asBool(cfg, "parse_json", false);
        boolean allowNonzeroStatus = asBool(cfg, "allow_nonzero_status", false);

        WebClient client = webClientBuilder.baseUrl(url).build();
        WebClient.RequestHeadersSpec<?> spec = client.get();

        Object rawHeaders = cfg.get("headers");
        if (rawHeaders instanceof Map<?, ?> headers) {
            for (Map.Entry<?, ?> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String hName = e.getKey().toString();
                String hValue = interpolate(e.getValue().toString(), run, input);
                spec = spec.header(hName, hValue);
            }
        }

        log.info("http_get[{}]: GET {}", blockConfig.getId(), url);
        long started = System.currentTimeMillis();

        ResponseEntity<String> response = spec
            .retrieve()
            .onStatus(status -> true, r -> reactor.core.publisher.Mono.empty())
            .toEntity(String.class)
            .timeout(Duration.ofSeconds(timeoutSec))
            .block();

        int durationMs = (int) (System.currentTimeMillis() - started);
        int status = response == null ? 0 : response.getStatusCode().value();
        String body = response == null ? "" : response.getBody();
        if (body == null) body = "";
        if (body.length() > MAX_BODY_BYTES) {
            body = body.substring(0, MAX_BODY_BYTES) + "\n... [body truncated]";
        }

        Map<String, String> flatHeaders = new LinkedHashMap<>();
        if (response != null) {
            HttpHeaders h = response.getHeaders();
            for (Map.Entry<String, List<String>> entry : h.entrySet()) {
                List<String> vals = entry.getValue();
                flatHeaders.put(entry.getKey(), vals.isEmpty() ? "" : vals.get(0));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("status", status);
        out.put("success", status >= 200 && status < 300);
        out.put("body", body);
        out.put("headers", flatHeaders);
        out.put("duration_ms", durationMs);

        if (parseJson && !body.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(body);
                // Convert to plain Java types so PathResolver can navigate without
                // leaking Jackson's JsonNode quirks into downstream blocks.
                out.put("json", objectMapper.convertValue(node, Object.class));
            } catch (Exception e) {
                log.warn("http_get[{}]: parse_json=true but body is not JSON: {}",
                    blockConfig.getId(), e.getMessage());
                out.put("json", null);
                out.put("json_parse_error", e.getMessage());
            }
        }

        if (!allowNonzeroStatus && (status < 200 || status >= 300)) {
            throw new RuntimeException(
                "http_get: " + url + " returned " + status + " (first 500 chars: "
                    + truncate(body, 500) + ")");
        }

        return out;
    }

    private String interpolate(String s, PipelineRun run, Map<String, Object> input) {
        return stringInterpolator != null
            ? stringInterpolator.interpolate(s, run, input)
            : s;
    }

    private static String asRequiredString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("http_get: config." + key + " is required");
        }
        return v.toString();
    }

    private static int asInt(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString().trim());
    }

    private static boolean asBool(Map<String, Object> cfg, String key, boolean def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
