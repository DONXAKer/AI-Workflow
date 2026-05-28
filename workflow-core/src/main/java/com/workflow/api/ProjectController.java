package com.workflow.api;

import com.workflow.core.PipelineRunRepository;
import com.workflow.core.RunStatus;
import com.workflow.knowledge.ProjectIndexService;
import com.workflow.knowledge.ProjectIndexer;
import com.workflow.preflight.PreflightCacheService;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import com.workflow.project.TemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            if (body.getTasksDir() != null) existing.setTasksDir(body.getTasksDir());
            existing.setOrchestratorEnabled(body.isOrchestratorEnabled());
            return ResponseEntity.ok(repository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");

    /**
     * Lists .md task files in the project's tasksDir (default: tasks/active).
     * Returns [{name, path, title}] — title is parsed from the first markdown heading.
     */
    @GetMapping("/{slug}/tasks")
    public ResponseEntity<List<Map<String, Object>>> listTasks(@PathVariable String slug) {
        return repository.findBySlug(slug).map(project -> {
            String workingDir = project.getWorkingDir();
            if (workingDir == null || workingDir.isBlank()) return ResponseEntity.ok(List.<Map<String, Object>>of());
            Path tasksPath = Paths.get(workingDir).resolve(project.getEffectiveTasksDir()).normalize();
            if (!Files.isDirectory(tasksPath)) return ResponseEntity.ok(List.<Map<String, Object>>of());
            List<Map<String, Object>> result = new ArrayList<>();
            try (var stream = Files.list(tasksPath)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        String title = extractTitle(p);
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", p.getFileName().toString());
                        entry.put("path", p.toString());
                        entry.put("title", title);
                        result.add(entry);
                    });
            } catch (IOException e) {
                // return what we have
            }
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    private String extractTitle(Path mdFile) {
        try (var lines = Files.lines(mdFile)) {
            return lines.limit(20)
                .map(l -> { Matcher m = MD_HEADING.matcher(l); return m.matches() ? m.group(1).trim() : null; })
                .filter(t -> t != null)
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
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
