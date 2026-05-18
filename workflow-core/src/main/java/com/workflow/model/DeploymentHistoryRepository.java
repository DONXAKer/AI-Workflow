package com.workflow.model;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeploymentHistoryRepository extends JpaRepository<DeploymentHistory, Long> {

    /**
     * Latest successful deployment for an environment in a given project. Used by
     * {@code DeployBlock} to populate "previous" snapshot and by introspection UIs.
     */
    @Query("""
            SELECT d FROM DeploymentHistory d
             WHERE d.environment = :environment
               AND (:projectSlug IS NULL OR d.projectSlug = :projectSlug)
               AND d.status = 'success'
             ORDER BY d.deployedAt DESC
            """)
    List<DeploymentHistory> findLatest(@Param("environment") String environment,
                                        @Param("projectSlug") String projectSlug,
                                        PageRequest page);

    default Optional<DeploymentHistory> findLatestSuccess(String environment, String projectSlug) {
        List<DeploymentHistory> hits = findLatest(environment, projectSlug, PageRequest.of(0, 1));
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * One step before {@code excludeArtifactId} — used by RollbackBlock to find what to roll
     * back TO when the latest deploy is the one currently broken. Skips entries with the same
     * artifact (the broken one) and returns the next-most-recent success.
     */
    @Query("""
            SELECT d FROM DeploymentHistory d
             WHERE d.environment = :environment
               AND (:projectSlug IS NULL OR d.projectSlug = :projectSlug)
               AND d.status = 'success'
               AND d.artifactId <> :excludeArtifactId
             ORDER BY d.deployedAt DESC
            """)
    List<DeploymentHistory> findPrevious(@Param("environment") String environment,
                                          @Param("projectSlug") String projectSlug,
                                          @Param("excludeArtifactId") String excludeArtifactId,
                                          PageRequest page);

    default Optional<DeploymentHistory> findPreviousSuccess(String environment, String projectSlug,
                                                               String excludeArtifactId) {
        List<DeploymentHistory> hits = findPrevious(environment, projectSlug, excludeArtifactId,
            PageRequest.of(0, 1));
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }
}
