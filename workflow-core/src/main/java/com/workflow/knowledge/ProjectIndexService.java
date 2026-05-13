package com.workflow.knowledge;

import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Façade over {@link ProjectIndexer} that resolves a project slug to its working dir
 * and exposes the operations the UI / API / pipeline lifecycle care about. Centralised
 * so {@code ProjectController} (manual reindex) and {@code PipelineRunner} (post-run
 * delta) share the same code path.
 *
 * <p>Async-job tracking: {@link #startReindexFullAsync} starts an in-flight job in a
 * background thread and stamps {@code jobs[slug]} with progress; {@link #getJobStatus}
 * returns the latest snapshot. The UI polls {@code /api/projects/{slug}/reindex/status}
 * to render a "indexing X of Y — currently embedding <file>" indicator that survives
 * page reloads (state is process-local, not page-local).
 */
@Service
public class ProjectIndexService {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndexService.class);

    private final ProjectRepository projectRepo;
    private final ProjectIndexer indexer;
    private final ProjectIndexEntryRepository entries;
    private final QdrantClient qdrant;

    /** Per-slug in-memory job state. Survives across UI page loads (until JVM restart). */
    private final Map<String, JobStatus> jobs = new ConcurrentHashMap<>();

    @Autowired
    public ProjectIndexService(ProjectRepository projectRepo,
                               ProjectIndexer indexer,
                               ProjectIndexEntryRepository entries,
                               QdrantClient qdrant) {
        this.projectRepo = projectRepo;
        this.indexer = indexer;
        this.entries = entries;
        this.qdrant = qdrant;
    }

    /** True when both Qdrant URL is set AND the embedding probe succeeded in the past. */
    public boolean isAvailable() { return qdrant.isEnabled(); }

    /** Quick "does the project have any indexed chunks yet" check. */
    public boolean isIndexed(String projectSlug) {
        if (projectSlug == null || projectSlug.isBlank()) return false;
        return entries.countByProjectSlug(projectSlug) > 0;
    }

    public ProjectIndexer.Report reindexFull(String projectSlug) {
        Path workingDir = resolveWorkingDir(projectSlug);
        if (workingDir == null) return new ProjectIndexer.Report(0, 0, 0, 0, 0);
        return indexer.reindexFull(projectSlug, workingDir, null);
    }

    public ProjectIndexer.Report reindexDelta(String projectSlug, List<String> changedRelPaths) {
        Path workingDir = resolveWorkingDir(projectSlug);
        if (workingDir == null) return new ProjectIndexer.Report(0, 0, 0, 0, 0);
        return indexer.reindexDelta(projectSlug, workingDir, changedRelPaths);
    }

    /**
     * Fire-and-forget full reindex with progress tracking. The first call starts a
     * background thread that updates {@link #jobs} as files are processed; subsequent
     * calls for the same slug return immediately if the job is still running.
     *
     * @return job state at the moment of call — caller polls {@link #getJobStatus} for updates.
     */
    public synchronized JobStatus startReindexFullAsync(String projectSlug) {
        JobStatus current = jobs.get(projectSlug);
        if (current != null && current.state() == JobState.RUNNING) {
            return current;  // already in progress — no double-start
        }
        Path workingDir = resolveWorkingDir(projectSlug);
        if (workingDir == null) {
            JobStatus err = JobStatus.failed("project has no workingDir");
            jobs.put(projectSlug, err);
            return err;
        }
        JobStatus start = JobStatus.running(0, 0, "", Instant.now());
        jobs.put(projectSlug, start);
        runReindexFullInBackground(projectSlug, workingDir);
        return start;
    }

    @Async
    public void runReindexFullInBackground(String projectSlug, Path workingDir) {
        long startedAt = System.currentTimeMillis();
        try {
            // Progress callback per file: stamp jobs map so the polling UI sees it.
            ProjectIndexer.ProgressCallback cb = (currentFile, processed, total) -> {
                jobs.put(projectSlug, JobStatus.running(processed, total, currentFile, Instant.now()));
            };
            ProjectIndexer.Report report = indexer.reindexFull(projectSlug, workingDir, cb);
            long tookMs = System.currentTimeMillis() - startedAt;
            jobs.put(projectSlug, JobStatus.done(report, tookMs, Instant.now()));
            log.info("reindexFull({}) done — {} files, {} chunks, {}ms",
                projectSlug, report.processed(), report.chunksUpserted(), tookMs);
        } catch (Exception e) {
            log.warn("reindexFull({}) failed: {}", projectSlug, e.getMessage());
            jobs.put(projectSlug, JobStatus.failed(e.getMessage() == null ? "unknown error" : e.getMessage()));
        }
    }

    /** Returns the current async-job state, or {@link JobStatus#idle()} if no job has run yet. */
    public JobStatus getJobStatus(String projectSlug) {
        JobStatus s = jobs.get(projectSlug);
        return s != null ? s : JobStatus.idle();
    }

    /**
     * Fire-and-forget background delta reindex — called by the pipeline runner after
     * a successful run. Failures here must not affect the operator's view of the run.
     */
    @Async
    public CompletableFuture<ProjectIndexer.Report> reindexDeltaAsync(String projectSlug,
                                                                     List<String> changedRelPaths) {
        try {
            return CompletableFuture.completedFuture(reindexDelta(projectSlug, changedRelPaths));
        } catch (Exception e) {
            log.warn("Async reindexDelta({}) failed: {}", projectSlug, e.getMessage());
            return CompletableFuture.completedFuture(new ProjectIndexer.Report(0, 0, 0, 0, 0));
        }
    }

    /** Snapshot stats for UI: how many files indexed, when was the latest entry written. */
    public IndexStats getStats(String projectSlug) {
        long count = entries.countByProjectSlug(projectSlug);
        return new IndexStats(count, qdrant.isEnabled());
    }

    private Path resolveWorkingDir(String projectSlug) {
        Optional<Project> p = projectRepo.findBySlug(projectSlug);
        if (p.isEmpty() || p.get().getWorkingDir() == null || p.get().getWorkingDir().isBlank()) {
            log.warn("reindex: project '{}' has no workingDir", projectSlug);
            return null;
        }
        return Paths.get(p.get().getWorkingDir()).toAbsolutePath();
    }

    public record IndexStats(long fileCount, boolean qdrantEnabled) {}

    public enum JobState { IDLE, RUNNING, DONE, FAILED }

    /** Snapshot of an async reindex job's progress. UI polls and renders these. */
    public record JobStatus(
        JobState state,
        int processed,
        int total,
        String currentFile,
        ProjectIndexer.Report report,
        Long tookMs,
        String error,
        Instant updatedAt) {

        public static JobStatus idle() {
            return new JobStatus(JobState.IDLE, 0, 0, "", null, null, null, null);
        }
        public static JobStatus running(int processed, int total, String currentFile, Instant updatedAt) {
            return new JobStatus(JobState.RUNNING, processed, total, currentFile, null, null, null, updatedAt);
        }
        public static JobStatus done(ProjectIndexer.Report report, long tookMs, Instant updatedAt) {
            return new JobStatus(JobState.DONE, report.processed(), report.processed(),
                "", report, tookMs, null, updatedAt);
        }
        public static JobStatus failed(String error) {
            return new JobStatus(JobState.FAILED, 0, 0, "", null, null, error, Instant.now());
        }
    }
}
