package com.workflow.preflight;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface PreflightSnapshotRepository extends JpaRepository<PreflightSnapshot, Long> {

    /**
     * Look up the freshest snapshot that matches the key. Newest first so a stale
     * snapshot with the same (slug, sha) doesn't shadow a newer one written after
     * a manual refresh.
     */
    @Query("SELECT s FROM PreflightSnapshot s WHERE s.projectSlug = :slug "
            + "AND s.mainCommitSha = :sha AND s.configHash = :hash "
            + "ORDER BY s.createdAt DESC")
    Optional<PreflightSnapshot> findFreshest(@Param("slug") String slug,
                                              @Param("sha") String mainCommitSha,
                                              @Param("hash") String configHash);

    @Modifying
    @Transactional
    @Query("DELETE FROM PreflightSnapshot s WHERE s.projectSlug = :slug")
    int deleteByProjectSlug(@Param("slug") String projectSlug);

    @Modifying
    @Transactional
    @Query("DELETE FROM PreflightSnapshot s WHERE s.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
