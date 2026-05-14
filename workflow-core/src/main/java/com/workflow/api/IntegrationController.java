package com.workflow.api;

import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.project.ProjectContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/integrations")
@PreAuthorize("hasRole('ADMIN')")
public class IntegrationController {

    @Autowired
    private IntegrationConfigRepository integrationConfigRepository;

    @GetMapping
    public List<IntegrationConfig> listAll() {
        return integrationConfigRepository.findByProjectSlug(ProjectContext.get());
    }

    @PostMapping
    public ResponseEntity<IntegrationConfig> create(@RequestBody IntegrationConfig config) {
        config.setProjectSlug(ProjectContext.get());
        IntegrationConfig saved = integrationConfigRepository.save(config);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<IntegrationConfig> getOne(@PathVariable Long id) {
        return integrationConfigRepository.findById(id)
            .filter(c -> ProjectContext.get().equals(c.getProjectSlug()))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<IntegrationConfig> update(@PathVariable Long id,
                                                     @RequestBody IntegrationConfig config) {
        return integrationConfigRepository.findById(id)
            .filter(c -> ProjectContext.get().equals(c.getProjectSlug()))
            .map(existing -> {
                config.setId(id);
                // Preserve scope — no cross-project moves allowed here.
                config.setProjectSlug(existing.getProjectSlug());
                return ResponseEntity.ok(integrationConfigRepository.save(config));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return integrationConfigRepository.findById(id)
            .filter(c -> ProjectContext.get().equals(c.getProjectSlug()))
            .map(c -> {
                integrationConfigRepository.delete(c);
                return ResponseEntity.noContent().<Void>build();
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnectivity(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();

        Optional<IntegrationConfig> configOpt = integrationConfigRepository.findById(id);
        if (configOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "Integration config not found: " + id);
            return ResponseEntity.ok(result);
        }

        IntegrationConfig config = configOpt.get();
        String testUrl = config.getBaseUrl();

        if (testUrl == null || testUrl.isBlank()) {
            result.put("success", false);
            result.put("message", "No base URL configured");
            return ResponseEntity.ok(result);
        }

        // Defensive normalisation — operators occasionally paste URLs with surrounding
        // whitespace, a trailing newline, U+00A0 non-breaking space (when copied from
        // rich-text), or Cyrillic look-alike characters from a placeholder hint. Java's
        // URI.create then throws "Illegal character in <part>" with no hint at the
        // offending byte, which is hard to debug from the UI.
        String normalized = testUrl.trim().replace(" ", "");
        // Strip any wrapping quote/bracket pairs from common paste-from-doc patterns.
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("<") && normalized.endsWith(">"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        // For OpenAI-compatible providers the bare baseUrl typically ends at `/v1`
        // which has no GET handler (404 from vLLM, OpenRouter, etc.). Probe `/models`
        // instead — it returns 200 on a healthy server (or 401 for auth-gated providers)
        // and lets us treat 4xx as a real "wrong URL" failure rather than swallowing
        // 404 as success.
        String probeUrl = normalized;
        boolean strictStatus = false;
        switch (config.getType()) {
            case VLLM:
            case OPENROUTER:
            case AITUNNEL:
            case OLLAMA:
                String stripped = normalized.replaceAll("/+$", "");
                if (!stripped.endsWith("/models")) {
                    probeUrl = (stripped.endsWith("/v1") ? stripped : stripped + "/v1") + "/models";
                }
                strictStatus = true;
                break;
            default:
                break;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(probeUrl))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            int code = response.statusCode();
            // 401 is acceptable for auth-gated providers (OpenRouter/AITunnel) — the URL
            // is correct, only the unauthenticated probe was rejected, which still proves
            // the service is reachable.
            boolean ok = strictStatus
                ? (code == 200 || code == 401)
                : (code < 500);
            if (ok) {
                result.put("success", true);
                result.put("message", code == 200
                    ? "Connected successfully (HTTP 200 " + probeUrl + ")"
                    : "Reachable but HTTP " + code + " from " + probeUrl);
            } else {
                result.put("success", false);
                result.put("message", "HTTP " + code + " from " + probeUrl
                    + " — wrong URL or service unhealthy");
            }
        } catch (IllegalArgumentException e) {
            // URI parse failure — provide explicit offset and a hex dump so the operator
            // can see hidden whitespace / Cyrillic characters that the UI strips visually.
            StringBuilder hex = new StringBuilder();
            for (byte b : testUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                hex.append(String.format("%02X ", b & 0xFF));
                if (hex.length() > 90) { hex.append("…"); break; }
            }
            result.put("success", false);
            result.put("message", "Malformed URL (" + e.getMessage() + ") — raw bytes: " + hex.toString().trim()
                + " — re-enter the URL by hand without copy-paste");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Connection failed: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}
