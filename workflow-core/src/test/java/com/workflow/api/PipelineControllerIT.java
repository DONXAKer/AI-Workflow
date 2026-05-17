package com.workflow.api;

import com.workflow.api.dto.PipelineInfo;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureWebMvc
class PipelineControllerIT {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockBean
    private ProjectRepository projectRepository;

    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void testListPipelines_withoutProject() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        mockMvc.perform(get("/api/pipelines"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"));
    }

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void testListPipelines_withProject() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Создаем мок проекта
        Project project = new Project();
        project.setSlug("test-project");
        project.setDisplayName("Test Project");
        
        // Создаем временную директорию для проекта
        Path tempDir = Files.createTempDirectory("test-project");
        project.setWorkingDir(tempDir.toString());

        when(projectRepository.findBySlug("test-project")).thenReturn(Optional.of(project));

        mockMvc.perform(get("/api/pipelines").param("projectSlug", "test-project"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"));

        // Очистка
        Files.deleteIfExists(tempDir);
    }
}