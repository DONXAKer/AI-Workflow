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

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 500) {
                result.put("success", true);
                result.put("message", "Connected successfully (HTTP " + response.statusCode() + ")");
            } else {
                result.put("success", false);
                result.put("message", "Server error: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Connection failed: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}
