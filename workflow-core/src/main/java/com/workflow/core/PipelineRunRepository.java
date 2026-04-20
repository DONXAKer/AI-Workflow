package com.workflow.core;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID>, JpaSpecificationExecutor<PipelineRun> {

    List<PipelineRun> findByPipelineNameOrderByStartedAtDesc(String pipelineName);

    long countByStatusIn(List<RunStatus> statuses);

    long countByStatusAndCompletedAtAfter(RunStatus status, Instant after);

    /**
     * Fallback count for runs that completed before the completedAt column was added.
     * Counts terminal runs where completedAt IS NULL but startedAt falls within the window.
     * Used alongside countByStatusAndCompletedAtAfter to avoid undercounting legacy rows.
     */
    long countByStatusAndCompletedAtIsNullAndStartedAtAfter(RunStatus status, Instant after);

    /**
     * Unfiltered summary page — no collection joins, so a page of N rows costs 2 queries
     * (count + data) instead of 2 + N*3.  blockCount is a correlated subquery.
     * Used when no filter parameters are present.
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
            ORDER BY r.started_at DESC
            """,
            countQuery = "SELECT COUNT(*) FROM pipeline_run",
            nativeQuery = true)
    Page<PipelineRunSummary> findAllSummary(Pageable pageable);

    /**
     * Filtered list path — loads completedBlocks in a single batch query via EntityGraph
     * rather than one SELECT per row.  Spring Data applies the named graph when Hibernate
     * generates the query for this method.
     */
    @EntityGraph("PipelineRun.withCompletedBlocks")
    Page<PipelineRun> findAll(Specification<PipelineRun> spec, Pageable pageable);

    /**
     * Detail endpoint: eagerly load all three collections in one fetch.
     * Using a dedicated method avoids altering the default findById behaviour.
     */
    @EntityGraph(attributePaths = {"completedBlocks", "autoApprove", "outputs"})
    Optional<PipelineRun> findWithCollectionsById(UUID id);

    List<PipelineRun> findByStatusAndPausedAtIsNotNullAndApprovalTimeoutSecondsIsNotNull(RunStatus status);
}
