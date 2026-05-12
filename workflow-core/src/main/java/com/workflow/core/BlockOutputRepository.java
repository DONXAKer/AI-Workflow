package com.workflow.core;

import org.springframework.data.domain.Pageable;
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

    /**
     * Returns previously-saved cache-source outputs matching the (scope, key) tuple, newest first.
     * Used by {@link BlockCacheService#lookup} to find reusable results across runs. Only rows
     * with {@code cacheable = true} qualify — cache hits and operator-edited outputs are excluded.
     */
    @Query("SELECT b FROM BlockOutput b WHERE b.cacheScope = :scope AND b.cacheKey = :key " +
           "AND b.cacheable = true ORDER BY b.completedAt DESC NULLS LAST, b.id DESC")
    List<BlockOutput> findCacheCandidates(@Param("scope") String scope, @Param("key") String key, Pageable pageable);
}
