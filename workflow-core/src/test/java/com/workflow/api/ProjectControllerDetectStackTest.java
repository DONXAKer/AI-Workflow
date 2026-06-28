package com.workflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import com.workflow.project.ProjectStackScanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
class ProjectControllerDetectStackTest {

    @Autowired
    private WebApplicationContext context;

    @MockBean
    private ProjectRepository repository;

    @MockBean
    private ProjectStackScanner projectStackScanner;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void detectStack_Success_ReturnsDetectedStack() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("test-project");
        project.setWorkingDir("/test/path");
        
        String detectedStack = "[{\"name\":\"java\",\"version\":\"17\"},{\"name\":\"spring-boot\",\"version\":\"3.1.0\"}]";
        
        when(repository.findBySlug("test-project")).thenReturn(Optional.of(project));
        when(projectStackScanner.scanProject("/test/path")).thenReturn(detectedStack);
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/test-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.techStack").value(detectedStack))
                .andExpect(jsonPath("$.workingDir").value("/test/path"));
    }

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void detectStack_ProjectNotFound_Returns404() throws Exception {
        // Arrange
        when(repository.findBySlug("nonexistent")).thenReturn(Optional.empty());
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/nonexistent/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void detectStack_NoWorkingDir_Returns400() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("test-project");
        project.setWorkingDir(null);
        
        when(repository.findBySlug("test-project")).thenReturn(Optional.of(project));
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/test-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Project working directory is not configured"));
    }

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void detectStack_EmptyWorkingDir_Returns400() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("test-project");
        project.setWorkingDir("   ");
        
        when(repository.findBySlug("test-project")).thenReturn(Optional.of(project));
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/test-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Project working directory is not configured"));
    }

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void detectStack_ScannerNotAvailable_Returns503() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("test-project");
        project.setWorkingDir("/test/path");
        
        when(repository.findBySlug("test-project")).thenReturn(Optional.of(project));
        when(projectStackScanner.scanProject(anyString())).thenThrow(new RuntimeException("Scanner error"));
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/test-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Failed to detect tech stack: Scanner error"));
    }

    @Test
    void detectStack_NoAuthentication_Returns401() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/projects/test-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"USER"})
    void detectStack_InsufficientPermissions_Returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/projects/test-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void detectStack_AdminCanAccess_Returns200() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("test-project");
        project.setWorkingDir("/test/path");
        
        String detectedStack = "[{\"name\":\"java\",\"version\":\"17\"}]";
        
        when(repository.findBySlug("test-project")).thenReturn(Optional.of(project));
        when(projectStackScanner.scanProject("/test/path")).thenReturn(detectedStack);
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/test-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(roles = {"RELEASE_MANAGER"})
    void detectStack_ReleaseManagerCanAccess_Returns200() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("test-project");
        project.setWorkingDir("/test/path");
        
        String detectedStack = "[{\"name\":\"java\",\"version\":\"17\"}]";
        
        when(repository.findBySlug("test-project")).thenReturn(Optional.of(project));
        when(projectStackScanner.scanProject("/test/path")).thenReturn(detectedStack);
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/test-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void detectStack_TypeScriptProject_ReturnsTypeScriptStack() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("typescript-project");
        project.setWorkingDir("/test/typescript-path");
        
        String detectedStack = "[{\"name\":\"typescript\",\"version\":\"5.0.0\"},{\"name\":\"react\",\"version\":\"18.2.0\"},{\"name\":\"vite\",\"version\":\"4.0.0\"}]";
        
        when(repository.findBySlug("typescript-project")).thenReturn(Optional.of(project));
        when(projectStackScanner.scanProject("/test/typescript-path")).thenReturn(detectedStack);
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/typescript-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.techStack").value(detectedStack))
                .andExpect(jsonPath("$.workingDir").value("/test/typescript-path"));
    }

    @Test
    @WithMockUser(roles = {"OPERATOR"})
    void detectStack_EmptyTechStack_ReturnsEmptyArray() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("empty-project");
        project.setWorkingDir("/test/empty-path");
        
        String detectedStack = "[]";
        
        when(repository.findBySlug("empty-project")).thenReturn(Optional.of(project));
        when(projectStackScanner.scanProject("/test/empty-path")).thenReturn(detectedStack);
        
        // Act & Assert
        mockMvc.perform(post("/api/projects/empty-project/detect-stack")
                .contentType(MediaType.APPLICATION_JSON).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.techStack").value("[]"))
                .andExpect(jsonPath("$.workingDir").value("/test/empty-path"));
    }

}