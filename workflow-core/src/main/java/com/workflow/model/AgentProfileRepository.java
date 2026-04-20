package com.workflow.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentProfileRepository extends JpaRepository<AgentProfile, Long> {
    Optional<AgentProfile> findByName(String name);
    Optional<AgentProfile> findByNameAndProjectSlug(String name, String projectSlug);

    /** Returns profiles visible in the given project: project-scoped + built-ins as fallback. */
    List<AgentProfile> findByProjectSlugOrBuiltinTrue(String projectSlug);
}
