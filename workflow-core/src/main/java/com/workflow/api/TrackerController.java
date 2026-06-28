package com.workflow.api;

import com.workflow.integrations.IntegrationResolver;
import com.workflow.integrations.tracker.TaskIssue;
import com.workflow.integrations.tracker.TaskTrackerRegistry;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationType;
import com.workflow.project.ProjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/tracker")
public class TrackerController {

    private static final Logger log = LoggerFactory.getLogger(TrackerController.class);

    private final TaskTrackerRegistry registry;
    private final IntegrationResolver resolver;

    public TrackerController(TaskTrackerRegistry registry, IntegrationResolver resolver) {
        this.registry = registry;
        this.resolver = resolver;
    }

    /**
     * Preview a task tracker issue — used by the Wizard UI to show issue details
     * before starting a pipeline run.
     *
     * GET /api/tracker/preview?issueId=PROJ-42&provider=youtrack&projectSlug=crm
     */
    @GetMapping("/preview")
    public ResponseEntity<?> previewIssue(
            @RequestParam String issueId,
            @RequestParam(defaultValue = "youtrack") String provider,
            @RequestParam(required = false) String projectSlug) {

        if (!registry.supports(provider)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unsupported tracker provider: " + provider));
        }

        // Resolve project context for project-scoped integration lookup
        if (projectSlug != null && !projectSlug.isBlank()) {
            ProjectContext.set(projectSlug);
        }

        IntegrationType type = resolveType(provider);
        Optional<IntegrationConfig> cfgOpt = resolver.resolve(null, type);
        if (cfgOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No integration config found for provider: " + provider));
        }

        IntegrationConfig cfg = cfgOpt.get();
        Map<String, Object> config = buildConfigMap(cfg);

        try {
            TaskIssue issue = registry.get(provider).fetchIssue(issueId, config);
            return ResponseEntity.ok(issue);
        } catch (Exception e) {
            log.warn("Failed to fetch issue {} from {}: {}", issueId, provider, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to fetch issue"));
        }
    }

    private Map<String, Object> buildConfigMap(IntegrationConfig cfg) {
        Map<String, Object> map = new HashMap<>();
        if (cfg.getBaseUrl() != null) map.put("baseUrl", cfg.getBaseUrl());
        if (cfg.getToken() != null) map.put("token", cfg.getToken());
        if (cfg.getProject() != null) map.put("project", cfg.getProject());
        if (cfg.getOwner() != null) map.put("owner", cfg.getOwner());
        if (cfg.getRepo() != null) map.put("repo", cfg.getRepo());
        return map;
    }

    private IntegrationType resolveType(String provider) {
        return switch (provider.toLowerCase()) {
            case "jira" -> IntegrationType.valueOf("JIRA");
            case "github" -> IntegrationType.GITHUB;
            case "gitlab" -> IntegrationType.GITLAB;
            default -> IntegrationType.YOUTRACK;
        };
    }
}
