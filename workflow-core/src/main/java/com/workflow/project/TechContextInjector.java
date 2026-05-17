package com.workflow.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Сервис для автоматической генерации tech-specific guidelines на основе Project.techStackJson.
 * 
 * Используется в AnalysisBlock, CodeGenerationBlock и AgentWithToolsBlock для инъекции
 * технологического контекста в промпты LLM.
 */
@Service
public class TechContextInjector {

    private static final Logger log = LoggerFactory.getLogger(TechContextInjector.class);

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Генерирует markdown-блок с рекомендациями для технологического стека проекта.
     * 
     * @param projectSlug Slug проекта
     * @return Markdown-строка с tech-specific guidelines или пустая строка, если techStackJson пуст
     */
    public String buildContext(String projectSlug) {
        if (projectSlug == null || projectSlug.isBlank()) {
            return "";
        }

        Project project = projectRepository.findBySlug(projectSlug).orElse(null);
        if (project == null) {
            log.debug("No project found for slug '{}'", projectSlug);
            return "";
        }

        String techStackJson = project.getTechStackJson();
        if (techStackJson == null || techStackJson.isBlank()) {
            log.debug("No tech stack configured for project '{}'", projectSlug);
            return "";
        }

        List<TechEntry> techStack;
        try {
            techStack = objectMapper.readValue(techStackJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse techStackJson for project '{}': {}", projectSlug, e.getMessage());
            return "";
        }

        if (techStack.isEmpty()) {
            return "";
        }

        return buildTechGuidelines(techStack);
    }

    /**
     * Строит markdown с рекомендациями для каждой технологии из стека.
     */
    private String buildTechGuidelines(List<TechEntry> techStack) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Технологический стек проекта\n\n");
        
        for (TechEntry tech : techStack) {
            String guidelines = loadTechGuidelines(tech.name());
            if (guidelines == null || guidelines.isBlank()) {
                log.debug("No tech guidelines found for '{}'", tech.name());
                continue;
            }
            
            String version = tech.version() != null && !tech.version().isBlank() 
                ? " (" + tech.version() + ")" : "";
            
            sb.append("### ").append(tech.name()).append(version).append("\n");
            sb.append(guidelines).append("\n\n");
            
            // Проверка длины после добавления каждой технологии
            if (sb.length() > 1500) {
                log.warn("Tech context exceeds 1500 characters for project, truncating");
                String truncationMessage = "\n\n[... контент обрезан по лимиту 1500 символов]";
                int maxContentLength = 1500 - truncationMessage.length();
                return sb.substring(0, Math.min(maxContentLength, sb.length())).trim() + truncationMessage;
            }
        }
        
        return sb.toString().trim();
    }

    /**
     * Загружает guidelines для технологии из файла ресурсов.
     * 
     * @param techName Имя технологии (например, "java", "spring-boot", "react")
     * @return Содержимое файла или null, если файл не найден
     */
    private String loadTechGuidelines(String techName) {
        String fileName = "tech-guidelines/" + techName.toLowerCase() + ".md";
        try (InputStream is = getClass().getResourceAsStream("/" + fileName)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.warn("Failed to load tech guidelines for '{}': {}", techName, e.getMessage());
            return null;
        }
    }
}