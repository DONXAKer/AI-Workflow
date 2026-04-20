package com.workflow.project;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ensures a default project exists on startup. Exists so existing single-tenant installs
 * keep working while new multi-project UI/API is iterated on.
 */
@Component
public class DefaultProjectInitializer {

    private static final Logger log = LoggerFactory.getLogger(DefaultProjectInitializer.class);

    @Autowired
    private ProjectRepository repository;

    @Value("${workflow.config-dir:./config}")
    private String configDir;

    @PostConstruct
    public void ensureDefault() {
        if (repository.findBySlug(Project.DEFAULT_SLUG).isPresent()) return;
        Project p = new Project();
        p.setSlug(Project.DEFAULT_SLUG);
        p.setDisplayName("Default Project");
        p.setDescription("Auto-created on first startup. Rename or add more via /api/projects.");
        p.setConfigDir(configDir);
        repository.save(p);
        log.info("Created default project (slug={})", Project.DEFAULT_SLUG);
    }
}
