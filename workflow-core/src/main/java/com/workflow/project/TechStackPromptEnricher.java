package com.workflow.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.config.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Enriches all blocks in a pipeline config with technology-specific system prompt context,
 * derived from the current project's techStack setting.
 *
 * Called once per run start, before execution begins. Templates live in
 * src/main/resources/tech-prompts/{name}.md and may use {version} as a placeholder.
 */
@Service
public class TechStackPromptEnricher {

    private static final Logger log = LoggerFactory.getLogger(TechStackPromptEnricher.class);

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public void enrich(PipelineConfig config, String projectSlug) {
        Project project = projectRepository.findBySlug(projectSlug).orElse(null);
        if (project == null || project.getTechStackJson() == null || project.getTechStackJson().isBlank()) return;

        List<TechEntry> techStack;
        try {
            techStack = objectMapper.readValue(project.getTechStackJson(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse techStackJson for project '{}': {}", projectSlug, e.getMessage());
            return;
        }

        String combined = buildCombinedPrompt(techStack);
        if (combined.isBlank()) return;

        for (BlockConfig block : config.getPipeline()) {
            if (block.getAgent() == null) block.setAgent(new AgentConfig());
            String existing = block.getAgent().getSystemPrompt();
            String enriched = (existing != null && !existing.isBlank())
                ? existing.stripTrailing() + "\n\n## Tech Stack\n" + combined
                : "## Tech Stack\n" + combined;
            block.getAgent().setSystemPrompt(enriched);
        }

        log.debug("Enriched {} blocks with tech stack context for project '{}'",
            config.getPipeline().size(), projectSlug);
    }

    private String buildCombinedPrompt(List<TechEntry> techStack) {
        StringBuilder sb = new StringBuilder();
        for (TechEntry tech : techStack) {
            String template = loadTemplate(tech.name());
            if (template == null) {
                log.debug("No tech-prompt template found for '{}'", tech.name());
                continue;
            }
            String version = tech.version() != null ? tech.version() : "";
            sb.append(template.replace("{version}", version)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String loadTemplate(String name) {
        try (InputStream is = getClass().getResourceAsStream("/tech-prompts/" + name + ".md")) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.warn("Failed to load tech-prompt template '{}': {}", name, e.getMessage());
            return null;
        }
    }
}
