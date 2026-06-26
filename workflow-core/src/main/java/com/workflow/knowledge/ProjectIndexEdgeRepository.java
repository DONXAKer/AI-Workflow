package com.workflow.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ProjectIndexEdgeRepository extends JpaRepository<ProjectIndexEdge, Long> {

    /** Outgoing edges: files that {@code fromPath} imports. */
    List<ProjectIndexEdge> findByProjectSlugAndFromPath(String projectSlug, String fromPath);

    /** Incoming edges: files that import {@code toPath}. */
    List<ProjectIndexEdge> findByProjectSlugAndToPath(String projectSlug, String toPath);

    long countByProjectSlug(String projectSlug);

    @Transactional
    void deleteByProjectSlug(String projectSlug);

    @Transactional
    void deleteByProjectSlugAndFromPath(String projectSlug, String fromPath);
}
