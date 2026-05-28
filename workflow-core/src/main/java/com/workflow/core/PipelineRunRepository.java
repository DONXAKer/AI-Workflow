package com.workflow.core;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID>, JpaSpecificationExecutor<PipelineRun> {

    List<PipelineRun> findByPipelineNameOrderByStartedAtDesc(String pipelineName);

    List<PipelineRun> findByStatusIn(List<RunStatus> statuses);

    long countByStatusIn(List<RunStatus> statuses);

    long countByProjectSlugAndStatusIn(String projectSlug, List<RunStatus> statuses);

    /** Active-run count for an account — drives the per-tier concurrent-run cap. */
    long countByAccountIdAndStatusIn(Long accountId, List<RunStatus> statuses);

    long countByProjectSlug(String projectSlug);

    long countByStatusAndCompletedAtAfter(RunStatus status, Instant after);

    long countByProjectSlugAndStatusAndCompletedAtAfter(String projectSlug, RunStatus status, Instant after);

    /**
     * Fallback count for runs that completed before the completedAt column was added.
     * Counts terminal runs where completedAt IS NULL but startedAt falls within the window.
     * Used alongside countByStatusAndCompletedAtAfter to avoid undercounting legacy rows.
     */
    long countByStatusAndCompletedAtIsNullAndStartedAtAfter(RunStatus status, Instant after);

    long countByProjectSlugAndStatusAndCompletedAtIsNullAndStartedAtAfter(String projectSlug, RunStatus status, Instant after);

    /**
     * Run-list query for {@code GET /api/runs}. A scalar projection — selects only the
     * summary columns, never the heavy TEXT blobs ({@code config_snapshot_json},
     * {@code skills_snapshot_json}, ...) — so a page of N rows costs 2 queries (count +
     * data) and a small payload, regardless of page size. {@code blockCount} is a
     * correlated subquery; no collection is loaded.
     *
     * <p>All filters are optional. {@code statuses} must be non-empty — the caller passes
     * every {@link RunStatus} name when the user applied no status filter, so {@code IN}
     * is never given an empty list. Nullable params are wrapped in {@code CAST(... )} so
     * PostgreSQL can infer their type in the {@code IS NULL} checks.
     */
    @Query(value = """
            SELECT CAST(r.id AS VARCHAR)            AS id,
                   r.pipeline_name                  AS pipelineName,
                   r.requirement                    AS requirement,
                   CAST(r.status AS VARCHAR)        AS status,
                   r.current_block                  AS currentBlock,
                   r.error                          AS error,
                   r.started_at                     AS startedAt,
                   r.completed_at                   AS completedAt,
                   (SELECT COUNT(*) FROM pipeline_run_completed_blocks cb
                    WHERE cb.run_id = r.id)         AS blockCount
            FROM pipeline_run r
            WHERE (:allProjects = TRUE OR r.project_slug = :projectSlug)
              AND r.status IN (:statuses)
              AND (CAST(:pipelineName AS VARCHAR) IS NULL OR r.pipeline_name = :pipelineName)
              AND (CAST(:search AS VARCHAR) IS NULL OR LOWER(r.requirement) LIKE :search)
              AND (CAST(:fromTs AS TIMESTAMP) IS NULL OR r.started_at >= :fromTs)
              AND (CAST(:toTs AS TIMESTAMP) IS NULL OR r.started_at < :toTs)
            ORDER BY r.started_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM pipeline_run r
            WHERE (:allProjects = TRUE OR r.project_slug = :projectSlug)
              AND r.status IN (:statuses)
              AND (CAST(:pipelineName AS VARCHAR) IS NULL OR r.pipeline_name = :pipelineName)
              AND (CAST(:search AS VARCHAR) IS NULL OR LOWER(r.requirement) LIKE :search)
              AND (CAST(:fromTs AS TIMESTAMP) IS NULL OR r.started_at >= :fromTs)
              AND (CAST(:toTs AS TIMESTAMP) IS NULL OR r.started_at < :toTs)
            """,
            nativeQuery = true)
    Page<PipelineRunSummary> findSummaryFiltered(
            @Param("allProjects") boolean allProjects,
            @Param("projectSlug") String projectSlug,
            @Param("statuses") List<String> statuses,
            @Param("pipelineName") String pipelineName,
            @Param("search") String search,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs,
            Pageable pageable);

    /**
     * Detail endpoint: eagerly load all three collections in one fetch.
     * Using a dedicated method avoids altering the default findById behaviour.
     */
    @EntityGraph(attributePaths = {"completedBlocks", "autoApprove", "outputs"})
    Optional<PipelineRun> findWithCollectionsById(UUID id);

    List<PipelineRun> findByStatusAndPausedAtIsNotNullAndApprovalTimeoutSecondsIsNotNull(RunStatus status);
}
