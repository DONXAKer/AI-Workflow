package com.workflow.api;

import com.workflow.core.PipelineRunRepository;
import com.workflow.core.RunStatus;
import com.workflow.knowledge.ProjectIndexService;
import com.workflow.knowledge.ProjectIndexer;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import com.workflow.preflight.PreflightCacheService;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import com.workflow.project.TemplateService;
import com.workflow.workspace.WorkspaceProvisioner;
import com.workflow.workspace.WorkspaceProvisioningException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    @Autowired
    private ProjectRepository repository;

    @Autowired(required = false)
    private ProjectIndexService indexService;

    @Autowired(required = false)
    private PreflightCacheService preflightCacheService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    @Autowired(required = false)
    private IntegrationConfigRepository integrationConfigRepository;

    @Autowired(required = false)
    private WorkspaceProvisioner workspaceProvisioner;

    @Value("${workflow.config-dir:./config}")
    private String globalConfigDir;

    @GetMapping
    public List<Project> list() {
        return repository.findAll();
    }

    /**
     * GET /api/projects/stats
     *
     * <p>Returns per-project run-status counters for the projects list page:
     * running, awaiting-approval, completed-today, failed-today. Uses
     * {@link PipelineRunRepository}'s {@code countByProjectSlugAndStatusIn}
     * variants for efficient aggregation (one query per (slug,statusBucket)
     * pair, no full-row materialization).
     */
    @GetMapping("/stats")
    public ResponseEntity<List<Map<String, Object>>> stats() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Project p : repository.findAll()) {
            String slug = p.getSlug();
            long running = pipelineRunRepository.countByProjectSlugAndStatusIn(
                slug, List.of(RunStatus.RUNNING));
            long awaiting = pipelineRunRepository.countByProjectSlugAndStatusIn(
                slug, List.of(RunStatus.PAUSED_FOR_APPROVAL));
            long completedToday = pipelineRunRepository
                .countByProjectSlugAndStatusAndCompletedAtAfter(slug, RunStatus.COMPLETED, startOfDay)
              + pipelineRunRepository
                .countByProjectSlugAndStatusAndCompletedAtIsNullAndStartedAtAfter(slug, RunStatus.COMPLETED, startOfDay);
            long failedToday = pipelineRunRepository
                .countByProjectSlugAndStatusAndCompletedAtAfter(slug, RunStatus.FAILED, startOfDay)
              + pipelineRunRepository
                .countByProjectSlugAndStatusAndCompletedAtIsNullAndStartedAtAfter(slug, RunStatus.FAILED, startOfDay);
            long total = pipelineRunRepository.countByProjectSlug(slug);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", slug);
            row.put("running", running);
            row.put("awaitingApproval", awaiting);
            row.put("completedToday", completedToday);
            row.put("failedToday", failedToday);
            row.put("total", total);
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{slug}")
    public ResponseEntity<Project> get(@PathVariable String slug) {
        return repository.findBySlug(slug)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Project> create(@RequestBody Project body) {
        if (repository.findBySlug(body.getSlug()).isPresent()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(repository.save(body));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{slug}")
    public ResponseEntity<Project> update(@PathVariable String slug, @RequestBody Project body) {
        return repository.findBySlug(slug).map(existing -> {
            if (body.getDisplayName() != null) existing.setDisplayName(body.getDisplayName());
            if (body.getDescription() != null) existing.setDescription(body.getDescription());
            if (body.getConfigDir() != null) existing.setConfigDir(body.getConfigDir());
            if (body.getWorkingDir() != null) existing.setWorkingDir(body.getWorkingDir());
            if (body.getOrchestratorModel() != null) existing.setOrchestratorModel(body.getOrchestratorModel());
            if (body.getOrchestratorSystemPromptExtra() != null) existing.setOrchestratorSystemPromptExtra(body.getOrchestratorSystemPromptExtra());
            if (body.getTechStackJson() != null) existing.setTechStackJson(body.getTechStackJson());
            if (body.getDefaultProvider() != null) existing.setDefaultProvider(body.getDefaultProvider());
            if (body.getDefaultTrackerProvider() != null) existing.setDefaultTrackerProvider(body.getDefaultTrackerProvider());
            existing.setOrchestratorEnabled(body.isOrchestratorEnabled());
            return ResponseEntity.ok(repository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{slug}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String slug) {
        return repository.findBySlug(slug).map(p -> {
            repository.delete(p);
            Map<String, Object> ok = Map.of("success", true);
            return ResponseEntity.ok(ok);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Starts an async full reindex of the project's source code. Returns immediately
     * with the initial job state — clients poll {@code GET .../reindex/status} for
     * progress. Re-clicking while a job is running is a no-op (returns current state).
     * Returns {@code 503} when the knowledge layer isn't configured.
     */
    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PostMapping("/{slug}/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable String slug) {
        if (indexService == null || !indexService.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of(
                "success", false,
                "error", "Knowledge layer not configured (workflow.knowledge.qdrant.url unset)"));
        }
        if (repository.findBySlug(slug).isEmpty()) return ResponseEntity.notFound().build();
        ProjectIndexService.JobStatus job = indexService.startReindexFullAsync(slug);
        return ResponseEntity.ok(toJobMap(job, true));
    }

    /** Live progress for the most recent reindex job. Survives page navigation. */
    @GetMapping("/{slug}/reindex/status")
    public ResponseEntity<Map<String, Object>> reindexStatus(@PathVariable String slug) {
        if (repository.findBySlug(slug).isEmpty()) return ResponseEntity.notFound().build();
        if (indexService == null) {
            return ResponseEntity.ok(Map.of("state", "idle", "qdrant_enabled", false));
        }
        return ResponseEntity.ok(toJobMap(indexService.getJobStatus(slug), false));
    }

    private static Map<String, Object> toJobMap(ProjectIndexService.JobStatus job, boolean withSuccess) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (withSuccess) m.put("success", job.state() != ProjectIndexService.JobState.FAILED);
        m.put("state", job.state().name().toLowerCase());
        m.put("processed", job.processed());
        m.put("total", job.total());
        m.put("current_file", job.currentFile() != null ? job.currentFile() : "");
        if (job.report() != null) {
            ProjectIndexer.Report r = job.report();
            m.put("skipped_unchanged", r.skippedUnchanged());
            m.put("removed_orphan", r.removedOrphan());
            m.put("chunks_upserted", r.chunksUpserted());
        }
        if (job.tookMs() != null) m.put("took_ms", job.tookMs());
        if (job.error() != null) m.put("error", job.error());
        if (job.updatedAt() != null) m.put("updated_at", job.updatedAt().toString());
        return m;
    }

    /** Lightweight static stats — fileCount + whether knowledge layer is wired up. */
    @GetMapping("/{slug}/index-stats")
    public ResponseEntity<Map<String, Object>> indexStats(@PathVariable String slug) {
        if (repository.findBySlug(slug).isEmpty()) return ResponseEntity.notFound().build();
        if (indexService == null) {
            return ResponseEntity.ok(Map.of("file_count", 0, "qdrant_enabled", false));
        }
        ProjectIndexService.IndexStats s = indexService.getStats(slug);
        return ResponseEntity.ok(Map.of(
            "file_count", s.fileCount(),
            "qdrant_enabled", s.qdrantEnabled()));
    }

    /**
     * Drop all cached preflight snapshots for this project. Used when the local
     * build environment changed in a way the cache can't auto-detect (JDK upgrade,
     * npm registry pin, etc.). Next pipeline run forces a fresh preflight execution.
     */
    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PostMapping("/{slug}/preflight/refresh")
    public ResponseEntity<Map<String, Object>> preflightRefresh(@PathVariable String slug) {
        if (repository.findBySlug(slug).isEmpty()) return ResponseEntity.notFound().build();
        if (preflightCacheService == null) {
            return ResponseEntity.ok(Map.of("removed", 0, "available", false));
        }
        int removed = preflightCacheService.invalidateForProject(slug);
        return ResponseEntity.ok(Map.of("removed", removed, "available", true));
    }

    /**
     * Self-serve connect-a-repo flow: the customer pastes a GitHub/GitLab HTTPS URL plus
     * an access token. We validate the token can reach the repo (JGit {@code ls-remote}),
     * create an account-scoped {@link Project} that is repo-backed (each run clones it into
     * an isolated sandbox), and auto-create the matching GitHub/GitLab integration so the
     * MR/PR block works without any manual setup.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(@RequestBody Map<String, Object> body) {
        String repoUrl = asStr(body.get("repoUrl"));
        String accessToken = asStr(body.get("accessToken"));
        String displayName = asStr(body.get("displayName"));
        String defaultBranch = asStr(body.get("defaultBranch"));
        if (repoUrl == null || accessToken == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "repoUrl and accessToken are required"));
        }
        RepoCoordinates coord = parseRepoUrl(repoUrl);
        if (coord == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Unsupported repository URL — expected a github.com or gitlab.* HTTPS URL"));
        }
        if (workspaceProvisioner != null) {
            try {
                workspaceProvisioner.validateAccess(repoUrl, accessToken);
            } catch (WorkspaceProvisioningException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        }

        String slug = uniqueSlug(coord.repo());
        Project project = new Project();
        project.setSlug(slug);
        project.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : coord.repo());
        project.setDescription("Connected from " + repoUrl);
        project.setConfigDir(globalConfigDir);
        project.setRepoUrl(repoUrl);
        project.setRepoProvider(coord.provider());
        project.setRepoToken(accessToken);
        project.setDefaultBranch(defaultBranch);
        project = repository.save(project);   // accountId set from TenantContext @PrePersist

        if (integrationConfigRepository != null) {
            IntegrationConfig ic = new IntegrationConfig();
            ic.setName(coord.provider() + "-" + slug);
            ic.setType("github".equals(coord.provider()) ? IntegrationType.GITHUB : IntegrationType.GITLAB);
            ic.setDisplayName(coord.provider() + " repository");
            ic.setBaseUrl("github".equals(coord.provider())
                ? "https://api.github.com" : "https://" + coord.host());
            ic.setToken(accessToken);
            ic.setOwner(coord.owner());
            ic.setRepo(coord.repo());
            ic.setProject(coord.owner() + "/" + coord.repo());
            ic.setDefault(true);
            ic.setProjectSlug(slug);
            integrationConfigRepository.save(ic);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("slug", project.getSlug());
        resp.put("displayName", project.getDisplayName());
        resp.put("repoProvider", coord.provider());
        return ResponseEntity.ok(resp);
    }

    /** Parsed GitHub/GitLab repository URL. */
    private record RepoCoordinates(String provider, String host, String owner, String repo) {}

    private static RepoCoordinates parseRepoUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        int scheme = u.indexOf("://");
        if (scheme < 0) return null;                       // only HTTPS URLs supported
        u = u.substring(scheme + 3);
        int slash = u.indexOf('/');
        if (slash < 0) return null;
        String host = u.substring(0, slash).toLowerCase();
        String path = u.substring(slash + 1);
        if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String[] parts = path.split("/");
        if (parts.length < 2) return null;
        String provider;
        if (host.contains("github")) provider = "github";
        else if (host.contains("gitlab")) provider = "gitlab";
        else return null;
        String repo = parts[parts.length - 1];
        String owner = String.join("/", Arrays.copyOf(parts, parts.length - 1));
        if (repo.isBlank() || owner.isBlank()) return null;
        return new RepoCoordinates(provider, host, owner, repo);
    }

    private String uniqueSlug(String base) {
        String slugified = base.toLowerCase().replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-+|-+$)", "");
        if (slugified.isBlank()) slugified = "project";
        String candidate = slugified;
        int n = 2;
        while (repository.findBySlug(candidate).isPresent()) {
            candidate = slugified + "-" + n++;
        }
        return candidate;
    }

    private static String asStr(Object o) {
        return o instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{slug}/apply-template")
    public ResponseEntity<Map<String, Object>> applyTemplate(
            @PathVariable String slug,
            @RequestBody Map<String, String> body) {
        var projectOpt = repository.findBySlug(slug);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();
        Project project = projectOpt.get();

        String templateId = body.get("templateId");
        if (templateId == null || templateId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "templateId is required"));
        }

        // Prefer workingDir/.ai-workflow/, fall back to global configDir
        Path configDir;
        if (project.getWorkingDir() != null && !project.getWorkingDir().isBlank()) {
            configDir = Paths.get(project.getWorkingDir()).resolve(".ai-workflow");
        } else {
            configDir = Paths.get(globalConfigDir);
        }

        try {
            Path written = templateService.applyTemplate(templateId, configDir);
            return ResponseEntity.ok(Map.of(
                "path", written.toString(),
                "fileName", written.getFileName().toString()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
