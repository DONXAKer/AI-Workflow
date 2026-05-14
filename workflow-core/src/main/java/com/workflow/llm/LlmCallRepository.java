package com.workflow.llm;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {

    Page<LlmCall> findByRunIdOrderByTimestampDesc(UUID runId, Pageable pageable);

    java.util.List<LlmCall> findByRunIdOrderByTimestampAsc(UUID runId);

    java.util.List<LlmCall> findByRunIdAndBlockId(UUID runId, String blockId);

    @Query("""
        SELECT new com.workflow.llm.LlmCostSummary(
          c.model,
          COUNT(c),
          COALESCE(SUM(c.tokensIn), 0),
          COALESCE(SUM(c.tokensOut), 0),
          COALESCE(SUM(c.costUsd), 0.0))
        FROM LlmCall c
        WHERE c.timestamp >= :from AND c.timestamp < :to
          AND c.projectSlug = :projectSlug
        GROUP BY c.model
        ORDER BY SUM(c.costUsd) DESC
        """)
    java.util.List<LlmCostSummary> summarizeByModelForProject(Instant from, Instant to, String projectSlug);

    /**
     * Sum cost of every {@link LlmCall} attached to this run, in USD. Used both for
     * the running-total badge on the run-detail page and for the per-run escalation
     * budget cap ({@code workflow.escalation.max-budget-usd}). Returns 0.0 when the
     * run has no calls.
     */
    @Query("SELECT COALESCE(SUM(c.costUsd), 0.0) FROM LlmCall c WHERE c.runId = :runId")
    double sumCostByRunId(UUID runId);
}
