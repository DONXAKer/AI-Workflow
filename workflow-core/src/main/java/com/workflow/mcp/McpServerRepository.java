package com.workflow.mcp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface McpServerRepository extends JpaRepository<McpServer, Long> {
    List<McpServer> findByProjectSlug(String projectSlug);
    List<McpServer> findByProjectSlugAndEnabled(String projectSlug, boolean enabled);
    List<McpServer> findByProjectSlugAndNameIn(String projectSlug, List<String> names);
}
