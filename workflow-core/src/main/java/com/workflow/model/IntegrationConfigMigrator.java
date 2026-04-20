package com.workflow.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IntegrationConfigMigrator {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConfigMigrator.class);

    @Autowired
    private IntegrationConfigRepository integrationConfigRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateFromEnvVars() {
        migrateYouTrack();
        migrateGitLab();
        migrateGitHub();
        migrateOpenRouter();
    }

    private void migrateYouTrack() {
        List<IntegrationConfig> existing = integrationConfigRepository.findByType(IntegrationType.YOUTRACK);
        if (!existing.isEmpty()) {
            return;
        }

        String url = System.getenv("YOUTRACK_URL");
        String token = System.getenv("YOUTRACK_TOKEN");
        String project = System.getenv("YOUTRACK_PROJECT");

        if (url != null && token != null) {
            IntegrationConfig config = new IntegrationConfig();
            config.setName("youtrack-default");
            config.setType(IntegrationType.YOUTRACK);
            config.setDisplayName("YouTrack (migrated from env)");
            config.setBaseUrl(url);
            config.setToken(token);
            config.setProject(project);
            config.setDefault(true);
            integrationConfigRepository.save(config);
            log.info("Migrated YouTrack config from environment variables");
        }
    }

    private void migrateGitLab() {
        List<IntegrationConfig> existing = integrationConfigRepository.findByType(IntegrationType.GITLAB);
        if (!existing.isEmpty()) {
            return;
        }

        String url = System.getenv("GITLAB_URL");
        String token = System.getenv("GITLAB_TOKEN");
        String projectId = System.getenv("GITLAB_PROJECT_ID");

        if (token != null) {
            IntegrationConfig config = new IntegrationConfig();
            config.setName("gitlab-default");
            config.setType(IntegrationType.GITLAB);
            config.setDisplayName("GitLab (migrated from env)");
            config.setBaseUrl(url != null ? url : "https://gitlab.com");
            config.setToken(token);
            config.setProject(projectId);
            config.setDefault(true);
            integrationConfigRepository.save(config);
            log.info("Migrated GitLab config from environment variables");
        }
    }

    private void migrateGitHub() {
        List<IntegrationConfig> existing = integrationConfigRepository.findByType(IntegrationType.GITHUB);
        if (!existing.isEmpty()) {
            return;
        }

        String token = System.getenv("GITHUB_TOKEN");
        String owner = System.getenv("GITHUB_OWNER");
        String repo = System.getenv("GITHUB_REPO");

        if (token != null) {
            IntegrationConfig config = new IntegrationConfig();
            config.setName("github-default");
            config.setType(IntegrationType.GITHUB);
            config.setDisplayName("GitHub (migrated from env)");
            config.setBaseUrl("https://api.github.com");
            config.setToken(token);
            config.setOwner(owner);
            config.setRepo(repo);
            config.setDefault(true);
            integrationConfigRepository.save(config);
            log.info("Migrated GitHub config from environment variables");
        }
    }

    private void migrateOpenRouter() {
        List<IntegrationConfig> existing = integrationConfigRepository.findByType(IntegrationType.OPENROUTER);
        if (!existing.isEmpty()) {
            return;
        }

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");

        if (apiKey != null) {
            IntegrationConfig config = new IntegrationConfig();
            config.setName("openrouter-default");
            config.setType(IntegrationType.OPENROUTER);
            config.setDisplayName("OpenRouter (migrated from env)");
            config.setBaseUrl(baseUrl != null ? baseUrl : "https://openrouter.ai/api/v1");
            config.setToken(apiKey);
            config.setDefault(true);
            integrationConfigRepository.save(config);
            log.info("Migrated OpenRouter config from environment variables");
        }
    }
}
