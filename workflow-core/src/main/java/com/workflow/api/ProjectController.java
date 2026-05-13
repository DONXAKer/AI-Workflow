package com.workflow.api;

import com.workflow.knowledge.ProjectIndexService;
import com.workflow.knowledge.ProjectIndexer;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public List<Project> list() {
        return repository.findAll();
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
}
