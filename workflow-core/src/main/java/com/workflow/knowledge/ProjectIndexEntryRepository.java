package com.workflow.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectIndexEntryRepository extends JpaRepository<ProjectIndexEntry, Long> {

    List<ProjectIndexEntry> findByProjectSlug(String projectSlug);

    Optional<ProjectIndexEntry> findByProjectSlugAndPath(String projectSlug, String path);

    long countByProjectSlug(String projectSlug);

    /** Used by the indexer to drop entries for files that no longer exist on disk. */
    void deleteByProjectSlugAndPath(String projectSlug, String path);
}
