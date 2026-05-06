package com.workflow.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BlockOutputRepository extends JpaRepository<BlockOutput, Long> {

    List<BlockOutput> findByRunId(UUID runId);

    List<BlockOutput> findByRunIdAndBlockId(UUID runId, String blockId);

    @Query("SELECT b FROM BlockOutput b WHERE b.run.id = :runId " +
           "ORDER BY CASE WHEN b.startedAt IS NULL THEN 1 ELSE 0 END ASC, b.startedAt ASC, b.id ASC")
    List<BlockOutput> findByRunIdOrderByStartedAt(@Param("runId") UUID runId);
}
