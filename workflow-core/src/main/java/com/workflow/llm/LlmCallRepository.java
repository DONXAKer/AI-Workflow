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
}
