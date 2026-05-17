package com.workflow.config;

import com.workflow.api.dto.PipelineInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineConfigLoaderTest {

    private PipelineConfigLoader loader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new PipelineConfigLoader();
    }

    @Test
    void testListPipelinesWithSources_platformOnly() throws IOException {
        // Создаем платформенную конфигурацию
        Path platformConfig = tempDir.resolve("platform.yaml");
        Files.writeString(platformConfig, """
            name: Test Pipeline
            description: Test description
            pipeline: []
            """);

        List<PipelineInfo> result = loader.listPipelinesWithSources(tempDir, null);

        assertEquals(1, result.size());
        PipelineInfo info = result.get(0);
        assertEquals("platform.yaml", info.name());
        assertEquals("platform", info.source());
        assertEquals("Test description", info.description());
        assertEquals("Test Pipeline", info.pipelineName());
    }

    @Test
    void testListPipelinesWithSources_withProjectConfigs() throws IOException {
        // Создаем платформенную конфигурацию
        Path platformConfig = tempDir.resolve("platform.yaml");
        Files.writeString(platformConfig, """
            name: Platform Pipeline
            description: Platform description
            pipeline: []
            """);

        // Создаем проектную директорию с .ai-workflow
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        Path aiWorkflowDir = projectDir.resolve(".ai-workflow");
        Files.createDirectories(aiWorkflowDir);

        // Создаем проектную конфигурацию
        Path projectConfig = aiWorkflowDir.resolve("project.yaml");
        Files.writeString(projectConfig, """
            name: Project Pipeline
            description: Project description
            pipeline: []
            """);

        List<PipelineInfo> result = loader.listPipelinesWithSources(tempDir, projectDir);

        assertEquals(2, result.size());
        
        // Проектные конфигурации должны быть первыми
        PipelineInfo first = result.get(0);
        assertEquals("project.yaml", first.name());
        assertEquals("project", first.source());
        assertEquals("Project description", first.description());

        PipelineInfo second = result.get(1);
        assertEquals("platform.yaml", second.name());
        assertEquals("platform", second.source());
        assertEquals("Platform description", second.description());
    }

    @Test
    void testListPipelinesWithSources_noDirectories() {
        List<PipelineInfo> result = loader.listPipelinesWithSources(
            tempDir.resolve("nonexistent"), null);

        assertTrue(result.isEmpty());
    }

    @Test
    void testListPipelinesWithSources_invalidConfig() throws IOException {
        // Создаем невалидный YAML
        Path invalidConfig = tempDir.resolve("invalid.yaml");
        Files.writeString(invalidConfig, "invalid: yaml: content:");

        List<PipelineInfo> result = loader.listPipelinesWithSources(tempDir, null);

        assertEquals(1, result.size());
        PipelineInfo info = result.get(0);
        assertEquals("invalid.yaml", info.name());
        assertEquals("platform", info.source());
        assertTrue(info.description().contains("Failed to load"));
        assertNull(info.pipelineName());
    }
}